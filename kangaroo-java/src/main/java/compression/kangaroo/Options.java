package compression.kangaroo;

/** Immutable configuration of this independent, non-author implementation. */
public final class Options {
    public enum Search { COMPACT, FAST }
    public final int window;
    public final Search search;
    public final boolean erasure;
    public final boolean rle;

    public Options(int window, Search search, boolean erasure, boolean rle) {
        if (window < 1 || window > 65536 || (window & (window - 1)) != 0)
            throw new IllegalArgumentException("Window must be a power of two in [1,65536]");
        if (search == null) throw new NullPointerException("search");
        this.window = window;
        this.search = search;
        this.erasure = erasure;
        this.rle = rle;
    }

    public static Options defaults() { return new Options(32, Search.FAST, true, true); }
    int windowBits() { return Integer.numberOfTrailingZeros(window); }
    int flags() { return (search == Search.FAST ? 1 : 0) | (erasure ? 2 : 0) | (rle ? 4 : 0); }
}
