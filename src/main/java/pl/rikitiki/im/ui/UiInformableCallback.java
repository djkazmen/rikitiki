package pl.rikitiki.im.ui;

public interface UiInformableCallback<T> extends UiCallback<T> {
    void inform(String text);
}
