package com.MoleLaw_backend.law.util;

import java.net.URI;
import java.util.regex.Pattern;

/** Public law viewer links, separate from authenticated DRF API endpoints. */
public final class LawSourceUrl {
    private static final Pattern MST = Pattern.compile("[0-9]{1,20}");

    private LawSourceUrl() {}

    public static String detail(String mst) {
        if (mst == null || !MST.matcher(mst).matches()) {
            throw new IllegalArgumentException("Invalid law MST");
        }
        return "https://www.law.go.kr/lsInfoP.do?lsiSeq=" + mst;
    }

    /** Correct legacy links without mutating immutable source snapshots or vector payloads. */
    public static String publicLink(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        if ("https".equalsIgnoreCase(uri.getScheme()) && "www.law.go.kr".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && "/법령".equals(uri.getPath())
                && uri.getRawQuery() != null) {
            String mst = null;
            for (String parameter : uri.getRawQuery().split("&")) {
                if (parameter.startsWith("lsiSeq=")) {
                    if (mst != null) throw new IllegalArgumentException("Duplicate law MST");
                    mst = parameter.substring("lsiSeq=".length());
                }
            }
            if (mst != null) return detail(mst);
        }
        return sourceUrl;
    }
}
