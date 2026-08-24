package pl.rikitiki.im.xmpp.jingle;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import pl.rikitiki.im.xml.Element;
import pl.rikitiki.im.xmpp.jingle.stanzas.Content;

/**
 * Translates between WebRTC's SDP-shaped types (SessionDescription,
 * IceCandidate) and the XEP-0167/0176/0320 Jingle XML that carries the same
 * information over XMPP. Pure data transformation, no WebRTC/XMPP I/O.
 */
public class SdpJingleTranslator {

	/**
	 * Fills the RTP description and ICE-UDP transport of `content` from a
	 * local WebRTC offer/answer, so it can be sent as a Jingle
	 * session-initiate/session-accept.
	 */
	public static void fillContentFromSdp(final Content content, final SessionDescription sdp) {
		final String[] lines = sdp.description.split("\r\n|\n");
		String ufrag = null;
		String pwd = null;
		String fingerprintHash = null;
		String fingerprintValue = null;
		String setup = null;
		boolean rtcpMux = false;
		final List<String> payloadIds = new ArrayList<>();
		final Map<String, Element> payloadTypes = new LinkedHashMap<>();
		boolean inAudioSection = false;
		for (String rawLine : lines) {
			final String line = rawLine.trim();
			if (line.startsWith("m=audio")) {
				inAudioSection = true;
				final String[] parts = line.split(" ");
				for (int i = 3; i < parts.length; i++) {
					payloadIds.add(parts[i]);
				}
				continue;
			}
			if (line.startsWith("m=")) {
				inAudioSection = false;
				continue;
			}
			if (!inAudioSection) {
				continue;
			}
			if (line.startsWith("a=ice-ufrag:")) {
				ufrag = line.substring("a=ice-ufrag:".length());
			} else if (line.startsWith("a=ice-pwd:")) {
				pwd = line.substring("a=ice-pwd:".length());
			} else if (line.startsWith("a=fingerprint:")) {
				final String rest = line.substring("a=fingerprint:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					fingerprintHash = rest.substring(0, sp);
					fingerprintValue = rest.substring(sp + 1);
				}
			} else if (line.startsWith("a=setup:")) {
				setup = line.substring("a=setup:".length());
			} else if (line.startsWith("a=rtcp-mux")) {
				rtcpMux = true;
			} else if (line.startsWith("a=rtpmap:")) {
				final String rest = line.substring("a=rtpmap:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					final String id = rest.substring(0, sp);
					final String[] codec = rest.substring(sp + 1).split("/");
					final Element pt = payloadType(payloadTypes, id);
					pt.setAttribute("name", codec[0]);
					if (codec.length > 1) {
						pt.setAttribute("clockrate", codec[1]);
					}
					if (codec.length > 2) {
						pt.setAttribute("channels", codec[2]);
					}
				}
			} else if (line.startsWith("a=fmtp:")) {
				final String rest = line.substring("a=fmtp:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					final String id = rest.substring(0, sp);
					final Element pt = payloadType(payloadTypes, id);
					for (final String kv : rest.substring(sp + 1).split(";")) {
						final int eq = kv.indexOf('=');
						final Element param = pt.addChild("parameter");
						if (eq > 0) {
							param.setAttribute("name", kv.substring(0, eq));
							param.setAttribute("value", kv.substring(eq + 1));
						} else {
							param.setAttribute("name", kv);
						}
					}
				}
			}
		}
		final Element description = content.setRtpDescription("audio");
		for (final String id : payloadIds) {
			final Element pt = payloadTypes.get(id);
			if (pt != null) {
				description.addChild(pt);
			}
		}
		if (rtcpMux) {
			description.addChild("rtcp-mux");
		}
		final Element transport = content.iceUdpTransport();
		if (ufrag != null) {
			transport.setAttribute("ufrag", ufrag);
		}
		if (pwd != null) {
			transport.setAttribute("pwd", pwd);
		}
		if (fingerprintHash != null) {
			final Element fingerprint = transport.addChild("fingerprint", Content.DTLS_NS);
			fingerprint.setAttribute("hash", fingerprintHash);
			fingerprint.setAttribute("setup", setup != null ? setup : "actpass");
			fingerprint.setContent(fingerprintValue);
		}
	}

	/**
	 * Multi-content variant of fillContentFromSdp, for sessions with more than
	 * one media section (e.g. audio+video calls). `contents` must already have
	 * one Content per m= section IN THE SAME ORDER they'll appear in the SDP —
	 * enforced by convention: WebRtcWrapper always adds the audio track before
	 * the video track, so createOffer/createAnswer emit m=audio before
	 * m=video, and JSEP requires the answer to preserve the offer's m-line
	 * order. Extra/missing m= sections relative to `contents` are ignored
	 * defensively rather than throwing.
	 */
	public static void fillContentsFromSdp(final List<Content> contents, final SessionDescription sdp) {
		final String[] lines = sdp.description.split("\r\n|\n");
		int sectionIndex = -1;
		String media = null;
		String ufrag = null;
		String pwd = null;
		String fingerprintHash = null;
		String fingerprintValue = null;
		String setup = null;
		boolean rtcpMux = false;
		List<String> payloadIds = new ArrayList<>();
		Map<String, Element> payloadTypes = new LinkedHashMap<>();

		for (String rawLine : lines) {
			final String line = rawLine.trim();
			if (line.startsWith("m=")) {
				if (sectionIndex >= 0 && sectionIndex < contents.size()) {
					applyParsedSection(contents.get(sectionIndex), media, payloadIds, payloadTypes,
							rtcpMux, ufrag, pwd, fingerprintHash, fingerprintValue, setup);
				}
				sectionIndex++;
				final String[] parts = line.split(" ");
				media = parts[0].substring("m=".length());
				payloadIds = new ArrayList<>();
				payloadTypes = new LinkedHashMap<>();
				ufrag = null;
				pwd = null;
				fingerprintHash = null;
				fingerprintValue = null;
				setup = null;
				rtcpMux = false;
				for (int i = 3; i < parts.length; i++) {
					payloadIds.add(parts[i]);
				}
				continue;
			}
			if (sectionIndex < 0) {
				continue;
			}
			if (line.startsWith("a=ice-ufrag:")) {
				ufrag = line.substring("a=ice-ufrag:".length());
			} else if (line.startsWith("a=ice-pwd:")) {
				pwd = line.substring("a=ice-pwd:".length());
			} else if (line.startsWith("a=fingerprint:")) {
				final String rest = line.substring("a=fingerprint:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					fingerprintHash = rest.substring(0, sp);
					fingerprintValue = rest.substring(sp + 1);
				}
			} else if (line.startsWith("a=setup:")) {
				setup = line.substring("a=setup:".length());
			} else if (line.startsWith("a=rtcp-mux")) {
				rtcpMux = true;
			} else if (line.startsWith("a=rtpmap:")) {
				final String rest = line.substring("a=rtpmap:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					final String id = rest.substring(0, sp);
					final String[] codec = rest.substring(sp + 1).split("/");
					final Element pt = payloadType(payloadTypes, id);
					pt.setAttribute("name", codec[0]);
					if (codec.length > 1) {
						pt.setAttribute("clockrate", codec[1]);
					}
					if (codec.length > 2) {
						pt.setAttribute("channels", codec[2]);
					}
				}
			} else if (line.startsWith("a=fmtp:")) {
				final String rest = line.substring("a=fmtp:".length());
				final int sp = rest.indexOf(' ');
				if (sp > 0) {
					final String id = rest.substring(0, sp);
					final Element pt = payloadType(payloadTypes, id);
					for (final String kv : rest.substring(sp + 1).split(";")) {
						final int eq = kv.indexOf('=');
						final Element param = pt.addChild("parameter");
						if (eq > 0) {
							param.setAttribute("name", kv.substring(0, eq));
							param.setAttribute("value", kv.substring(eq + 1));
						} else {
							param.setAttribute("name", kv);
						}
					}
				}
			}
		}
		if (sectionIndex >= 0 && sectionIndex < contents.size()) {
			applyParsedSection(contents.get(sectionIndex), media, payloadIds, payloadTypes,
					rtcpMux, ufrag, pwd, fingerprintHash, fingerprintValue, setup);
		}
	}

	private static void applyParsedSection(final Content content, final String media,
			final List<String> payloadIds, final Map<String, Element> payloadTypes, final boolean rtcpMux,
			final String ufrag, final String pwd, final String fingerprintHash, final String fingerprintValue,
			final String setup) {
		final Element description = content.setRtpDescription(media != null ? media : "audio");
		for (final String id : payloadIds) {
			final Element pt = payloadTypes.get(id);
			if (pt != null) {
				description.addChild(pt);
			}
		}
		if (rtcpMux) {
			description.addChild("rtcp-mux");
		}
		final Element transport = content.iceUdpTransport();
		if (ufrag != null) {
			transport.setAttribute("ufrag", ufrag);
		}
		if (pwd != null) {
			transport.setAttribute("pwd", pwd);
		}
		if (fingerprintHash != null) {
			final Element fingerprint = transport.addChild("fingerprint", Content.DTLS_NS);
			fingerprint.setAttribute("hash", fingerprintHash);
			fingerprint.setAttribute("setup", setup != null ? setup : "actpass");
			fingerprint.setContent(fingerprintValue);
		}
	}

	private static Element payloadType(final Map<String, Element> payloadTypes, final String id) {
		Element pt = payloadTypes.get(id);
		if (pt == null) {
			pt = new Element("payload-type");
			pt.setAttribute("id", id);
			payloadTypes.put(id, pt);
		}
		return pt;
	}

	/**
	 * Builds a full SDP offer/answer from a received (or our own, for
	 * symmetry) Jingle RTP content, so it can be handed to WebRTC's
	 * setRemoteDescription/setLocalDescription.
	 */
	public static SessionDescription contentToSdp(final Content content, final SessionDescription.Type type) {
		final Element description = content.getRtpDescription();
		final Element transport = content.iceUdpTransport();
		final StringBuilder sdp = new StringBuilder();
		sdp.append("v=0\r\n");
		sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
		sdp.append("s=-\r\n");
		sdp.append("t=0 0\r\n");
		sdp.append("a=group:BUNDLE 0\r\n");

		final List<Element> payloadTypes = new ArrayList<>();
		if (description != null) {
			for (final Element child : description.getChildren()) {
				if ("payload-type".equals(child.getName())) {
					payloadTypes.add(child);
				}
			}
		}
		final StringBuilder ids = new StringBuilder();
		for (final Element pt : payloadTypes) {
			ids.append(' ').append(pt.getAttribute("id"));
		}
		sdp.append("m=audio 9 UDP/TLS/RTP/SAVPF").append(ids).append("\r\n");
		sdp.append("c=IN IP4 0.0.0.0\r\n");
		sdp.append("a=rtcp:9 IN IP4 0.0.0.0\r\n");
		final String ufrag = transport == null ? null : transport.getAttribute("ufrag");
		final String pwd = transport == null ? null : transport.getAttribute("pwd");
		if (ufrag != null) {
			sdp.append("a=ice-ufrag:").append(ufrag).append("\r\n");
		}
		if (pwd != null) {
			sdp.append("a=ice-pwd:").append(pwd).append("\r\n");
		}
		final Element fingerprint = transport == null ? null : transport.findChild("fingerprint", Content.DTLS_NS);
		if (fingerprint != null) {
			sdp.append("a=fingerprint:").append(fingerprint.getAttribute("hash")).append(' ')
					.append(fingerprint.getContent()).append("\r\n");
			final String setup = fingerprint.getAttribute("setup");
			sdp.append("a=setup:").append(setup != null ? setup : "actpass").append("\r\n");
		}
		sdp.append("a=mid:0\r\n");
		sdp.append("a=sendrecv\r\n");
		if (description != null && description.hasChild("rtcp-mux")) {
			sdp.append("a=rtcp-mux\r\n");
		}
		for (final Element pt : payloadTypes) {
			final String id = pt.getAttribute("id");
			final String name = pt.getAttribute("name");
			final String clockrate = pt.getAttribute("clockrate");
			final String channels = pt.getAttribute("channels");
			sdp.append("a=rtpmap:").append(id).append(' ').append(name).append('/')
					.append(clockrate != null ? clockrate : "8000");
			if (channels != null && !"1".equals(channels)) {
				sdp.append('/').append(channels);
			}
			sdp.append("\r\n");
			final List<String> params = new ArrayList<>();
			for (final Element child : pt.getChildren()) {
				if ("parameter".equals(child.getName())) {
					final String pname = child.getAttribute("name");
					final String pvalue = child.getAttribute("value");
					params.add(pvalue != null ? pname + "=" + pvalue : pname);
				}
			}
			if (!params.isEmpty()) {
				sdp.append("a=fmtp:").append(id).append(' ').append(joinParams(params)).append("\r\n");
			}
		}
		if (transport != null) {
			for (final Element child : transport.getChildren()) {
				if ("candidate".equals(child.getName())) {
					sdp.append(candidateToSdpLine(child)).append("\r\n");
				}
			}
		}
		return new SessionDescription(type, sdp.toString());
	}

	/**
	 * Multi-content variant of contentToSdp, for sessions with more than one
	 * media section. Builds one m= line per content, in list order, bundled
	 * together under a single a=group:BUNDLE line. mid is the content's list
	 * index (audio=0, video=1 by convention — see fillContentsFromSdp).
	 */
	public static SessionDescription contentsToSdp(final List<Content> contents, final SessionDescription.Type type) {
		final StringBuilder sdp = new StringBuilder();
		sdp.append("v=0\r\n");
		sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
		sdp.append("s=-\r\n");
		sdp.append("t=0 0\r\n");
		final StringBuilder bundleMids = new StringBuilder("a=group:BUNDLE");
		for (int i = 0; i < contents.size(); i++) {
			bundleMids.append(' ').append(i);
		}
		sdp.append(bundleMids).append("\r\n");
		for (int mid = 0; mid < contents.size(); mid++) {
			appendMediaSection(sdp, contents.get(mid), mid);
		}
		return new SessionDescription(type, sdp.toString());
	}

	private static void appendMediaSection(final StringBuilder sdp, final Content content, final int mid) {
		final Element description = content.getRtpDescription();
		final Element transport = content.iceUdpTransport();
		final String media = description != null ? description.getAttribute("media") : null;

		final List<Element> payloadTypes = new ArrayList<>();
		if (description != null) {
			for (final Element child : description.getChildren()) {
				if ("payload-type".equals(child.getName())) {
					payloadTypes.add(child);
				}
			}
		}
		final StringBuilder ids = new StringBuilder();
		for (final Element pt : payloadTypes) {
			ids.append(' ').append(pt.getAttribute("id"));
		}
		sdp.append("m=").append(media != null ? media : "audio").append(" 9 UDP/TLS/RTP/SAVPF").append(ids).append("\r\n");
		sdp.append("c=IN IP4 0.0.0.0\r\n");
		sdp.append("a=rtcp:9 IN IP4 0.0.0.0\r\n");
		final String ufrag = transport == null ? null : transport.getAttribute("ufrag");
		final String pwd = transport == null ? null : transport.getAttribute("pwd");
		if (ufrag != null) {
			sdp.append("a=ice-ufrag:").append(ufrag).append("\r\n");
		}
		if (pwd != null) {
			sdp.append("a=ice-pwd:").append(pwd).append("\r\n");
		}
		final Element fingerprint = transport == null ? null : transport.findChild("fingerprint", Content.DTLS_NS);
		if (fingerprint != null) {
			sdp.append("a=fingerprint:").append(fingerprint.getAttribute("hash")).append(' ')
					.append(fingerprint.getContent()).append("\r\n");
			final String setup = fingerprint.getAttribute("setup");
			sdp.append("a=setup:").append(setup != null ? setup : "actpass").append("\r\n");
		}
		sdp.append("a=mid:").append(mid).append("\r\n");
		sdp.append("a=sendrecv\r\n");
		if (description != null && description.hasChild("rtcp-mux")) {
			sdp.append("a=rtcp-mux\r\n");
		}
		for (final Element pt : payloadTypes) {
			final String id = pt.getAttribute("id");
			final String name = pt.getAttribute("name");
			final String clockrate = pt.getAttribute("clockrate");
			final String channels = pt.getAttribute("channels");
			sdp.append("a=rtpmap:").append(id).append(' ').append(name).append('/')
					.append(clockrate != null ? clockrate : "8000");
			if (channels != null && !"1".equals(channels)) {
				sdp.append('/').append(channels);
			}
			sdp.append("\r\n");
			final List<String> params = new ArrayList<>();
			for (final Element child : pt.getChildren()) {
				if ("parameter".equals(child.getName())) {
					final String pname = child.getAttribute("name");
					final String pvalue = child.getAttribute("value");
					params.add(pvalue != null ? pname + "=" + pvalue : pname);
				}
			}
			if (!params.isEmpty()) {
				sdp.append("a=fmtp:").append(id).append(' ').append(joinParams(params)).append("\r\n");
			}
		}
		if (transport != null) {
			for (final Element child : transport.getChildren()) {
				if ("candidate".equals(child.getName())) {
					sdp.append(candidateToSdpLine(child)).append("\r\n");
				}
			}
		}
	}

	/**
	 * Builds an answer SDP for a video call where the callee's session-accept
	 * only carried one &lt;content/&gt; — e.g. a pre-video client that doesn't
	 * understand the second (video) content and silently dropped it. Our own
	 * local offer already committed two m-lines (audio, video) to WebRTC, and
	 * JSEP requires the answer to match that count/order, so a plain
	 * single-section answer would be rejected by setRemoteDescription with
	 * "order of m-lines in answer doesn't match order in offer". This
	 * reproduces the real (accepted) audio section and appends a rejected
	 * (port 0, inactive) video section in its place instead, which satisfies
	 * WebRTC while telling it the callee doesn't want video.
	 */
	public static SessionDescription contentsToSdpWithRejectedVideo(final Content acceptedAudioContent, final SessionDescription.Type type) {
		final StringBuilder sdp = new StringBuilder();
		sdp.append("v=0\r\n");
		sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
		sdp.append("s=-\r\n");
		sdp.append("t=0 0\r\n");
		sdp.append("a=group:BUNDLE 0\r\n");
		appendMediaSection(sdp, acceptedAudioContent, 0);
		sdp.append("m=video 0 UDP/TLS/RTP/SAVPF 0\r\n");
		sdp.append("c=IN IP4 0.0.0.0\r\n");
		sdp.append("a=inactive\r\n");
		sdp.append("a=mid:1\r\n");
		return new SessionDescription(type, sdp.toString());
	}

	private static String joinParams(final List<String> params) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				sb.append(';');
			}
			sb.append(params.get(i));
		}
		return sb.toString();
	}

	private static String candidateToSdpLine(final Element candidate) {
		final StringBuilder line = new StringBuilder("a=candidate:");
		line.append(candidate.getAttribute("foundation")).append(' ');
		line.append(candidate.getAttribute("component")).append(' ');
		line.append(candidate.getAttribute("protocol")).append(' ');
		line.append(candidate.getAttribute("priority")).append(' ');
		line.append(candidate.getAttribute("ip")).append(' ');
		line.append(candidate.getAttribute("port")).append(' ');
		line.append("typ ").append(candidate.getAttribute("type"));
		final String relAddr = candidate.getAttribute("rel-addr");
		final String relPort = candidate.getAttribute("rel-port");
		if (relAddr != null && relPort != null) {
			line.append(" raddr ").append(relAddr).append(" rport ").append(relPort);
		}
		final String generation = candidate.getAttribute("generation");
		line.append(" generation ").append(generation == null ? "0" : generation);
		return line.toString();
	}

	/**
	 * Converts a locally-gathered WebRTC ICE candidate into the XEP-0176
	 * <candidate/> shape, for sending as a Jingle transport-info.
	 */
	public static Element iceCandidateToJingle(final IceCandidate iceCandidate, final String cid) {
		final String[] tokens = iceCandidate.sdp.split(" ");
		final Element candidate = new Element("candidate");
		candidate.setAttribute("foundation", tokens[0].substring("candidate:".length()));
		candidate.setAttribute("component", tokens[1]);
		candidate.setAttribute("protocol", tokens[2].toLowerCase(Locale.US));
		candidate.setAttribute("priority", tokens[3]);
		candidate.setAttribute("ip", tokens[4]);
		candidate.setAttribute("port", tokens[5]);
		candidate.setAttribute("generation", "0");
		candidate.setAttribute("id", cid);
		candidate.setAttribute("network", "1");
		for (int i = 6; i < tokens.length - 1; i++) {
			if ("typ".equals(tokens[i])) {
				candidate.setAttribute("type", tokens[i + 1]);
			} else if ("raddr".equals(tokens[i])) {
				candidate.setAttribute("rel-addr", tokens[i + 1]);
			} else if ("rport".equals(tokens[i])) {
				candidate.setAttribute("rel-port", tokens[i + 1]);
			}
		}
		return candidate;
	}

	/**
	 * Converts a received XEP-0176 <candidate/> back into a WebRTC
	 * IceCandidate, for feeding into addIceCandidate().
	 */
	public static IceCandidate jingleCandidateToIce(final Element candidate, final String sdpMid, final int sdpMLineIndex) {
		final StringBuilder sdp = new StringBuilder("candidate:");
		sdp.append(candidate.getAttribute("foundation")).append(' ');
		sdp.append(candidate.getAttribute("component")).append(' ');
		sdp.append(candidate.getAttribute("protocol")).append(' ');
		sdp.append(candidate.getAttribute("priority")).append(' ');
		sdp.append(candidate.getAttribute("ip")).append(' ');
		sdp.append(candidate.getAttribute("port")).append(' ');
		sdp.append("typ ").append(candidate.getAttribute("type"));
		return new IceCandidate(sdpMid, sdpMLineIndex, sdp.toString());
	}
}
