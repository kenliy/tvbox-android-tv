package com.tvbox.desktop.tv;

import java.util.ArrayList;
import java.util.List;

final class Models {
    private Models() {}

    static final class Site {
        final String key;
        final String name;
        final String api;

        Site(String key, String name, String api) {
            this.key = key;
            this.name = name;
            this.api = api;
        }
    }

    static class Vod {
        String id = "";
        String name = "";
        String pic = "";
        String remarks = "";
        String year = "";
        String area = "";
        String actor = "";
        String director = "";
        String typeName = "";
        String score = "";
        Site site;
    }

    static final class Episode {
        final String name;
        final String url;
        final int index;
        final long introSkipMs;

        Episode(String name, String url, int index) {
            this(name, url, index, 0L);
        }

        Episode(String name, String url, int index, long introSkipMs) {
            this.name = name;
            this.url = url;
            this.index = index;
            this.introSkipMs = introSkipMs;
        }
    }

    static final class Line {
        final String name;
        final List<Episode> episodes = new ArrayList<>();
        long probeLatencyMs = -1L;
        boolean probeFailed;

        Line(String name) {
            this.name = name;
        }
    }

    static final class Detail extends Vod {
        String content = "";
        final List<Line> lines = new ArrayList<>();
    }

    static final class HomeSection {
        final String title;
        final List<Vod> items;
        final String error;

        HomeSection(String title, List<Vod> items, String error) {
            this.title = title;
            this.items = items;
            this.error = error;
        }
    }
}
