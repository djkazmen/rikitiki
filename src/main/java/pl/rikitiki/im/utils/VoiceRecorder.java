package pl.rikitiki.im.utils;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import pl.rikitiki.im.Config;

/**
 * Records mono AAC/M4A voice messages via AudioRecord + MediaCodec + MediaMuxer
 * instead of android.media.MediaRecorder. Several OEM MediaRecorder implementations
 * (observed on Samsung One UI) silently produce a corrupt/unparseable output container
 * even when all encoder parameters are set explicitly; driving the encoder and muxer
 * ourselves avoids that vendor-specific code path entirely.
 *
 * stop()/cancel() only flag the background encode thread to wind down; completion is
 * delivered asynchronously via Callback on the main thread so callers never block the
 * UI thread waiting for the encoder/muxer to flush and finalize the container.
 */
public class VoiceRecorder {

	private static final String MIME_TYPE = "audio/mp4a-latm";
	private static final int SAMPLE_RATE = 44100;
	private static final int CHANNEL_COUNT = 1;
	private static final int BIT_RATE = 96000;
	private static final int TIMEOUT_US = 10000;
	private static final int READ_CHUNK_SIZE = 4096;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private volatile boolean stopRequested = false;

	public interface Callback {
		void onStopped();
		void onError(Exception e);
	}

	public void start(final File outputFile, final Callback callback) throws IOException {
		final int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		if (minBufferSize <= 0) {
			throw new IOException("unable to determine AudioRecord buffer size");
		}

		final AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
				SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
				Math.max(minBufferSize, READ_CHUNK_SIZE) * 2);
		if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
			audioRecord.release();
			throw new IOException("could not initialize AudioRecord");
		}

		final MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
		format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_CHUNK_SIZE);

		final MediaCodec codec;
		final MediaMuxer muxer;
		try {
			codec = MediaCodec.createEncoderByType(MIME_TYPE);
			codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		} catch (final IOException e) {
			audioRecord.release();
			throw e;
		}

		stopRequested = false;

		codec.start();
		audioRecord.startRecording();

		final Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				runEncodeLoop(audioRecord, codec, muxer, callback);
			}
		}, "voice-recorder");
		thread.start();
	}

	private void runEncodeLoop(final AudioRecord audioRecord, final MediaCodec codec, final MediaMuxer muxer,
			final Callback callback) {
		final byte[] pcmBuffer = new byte[READ_CHUNK_SIZE];
		final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		int muxerTrackIndex = -1;
		boolean muxerStarted = false;
		boolean sawInputEos = false;
		boolean sawOutputEos = false;
		long totalSamplesRead = 0;
		Exception error = null;
		try {
			while (!sawOutputEos) {
				if (!sawInputEos) {
					final int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
					if (inputBufferIndex >= 0) {
						final ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
						inputBuffer.clear();
						final int bytesRead = stopRequested ? -1 : audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
						if (bytesRead < 0) {
							codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							sawInputEos = true;
						} else {
							inputBuffer.put(pcmBuffer, 0, bytesRead);
							final long presentationTimeUs = (totalSamplesRead * 1000000L) / SAMPLE_RATE;
							totalSamplesRead += bytesRead / 2L;
							codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0);
						}
					}
				}
				final int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
				if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					muxerTrackIndex = muxer.addTrack(codec.getOutputFormat());
					muxer.start();
					muxerStarted = true;
				} else if (outputBufferIndex >= 0) {
					final ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
					if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						bufferInfo.size = 0;
					}
					if (bufferInfo.size > 0 && muxerStarted) {
						outputBuffer.position(bufferInfo.offset);
						outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
						muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo);
					}
					codec.releaseOutputBuffer(outputBufferIndex, false);
					if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						sawOutputEos = true;
					}
				}
			}
		} catch (final Exception e) {
			Log.d(Config.LOGTAG, "voice recorder encode loop failed", e);
			error = e;
		} finally {
			try {
				audioRecord.stop();
			} catch (final Exception ignored) {
			}
			audioRecord.release();
			try {
				codec.stop();
			} catch (final Exception ignored) {
			}
			codec.release();
			if (muxerStarted) {
				try {
					muxer.stop();
				} catch (final Exception e) {
					if (error == null) {
						error = e;
					}
				}
			}
			muxer.release();
		}
		final Exception finalError = error;
		if (callback != null) {
			mainHandler.post(new Runnable() {
				@Override
				public void run() {
					if (finalError != null) {
						callback.onError(finalError);
					} else {
						callback.onStopped();
					}
				}
			});
		}
	}

	public void stop() {
		stopRequested = true;
	}

	public void cancel() {
		stopRequested = true;
	}
}
