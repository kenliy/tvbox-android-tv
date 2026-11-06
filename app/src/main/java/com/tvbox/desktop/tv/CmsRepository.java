package com.tvbox.desktop.tv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class CmsRepository {
    interface Callback<T> {
        void onResult(T value, String error);
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 8.0; Android TV) AppleWebKit/537.36 TVBoxAndroid/0.1";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int PROBE_CONNECT_TIMEOUT_MS = 1_500;
    private static final int PROBE_READ_TIMEOUT_MS = 1_800;
    private static final int PROBE_MANIFEST_LIMIT = 256 * 1024;
    private static final int PROBE_MEDIA_BYTES = 48 * 1024;

    private static final Models.Site FFE = new Models.Site(
            "ffzy", "非凡资源", "https://api.ffzyapi.com/api.php/provide/vod");
    private static final Models.Site LZI = new Models.Site(
            "lzi", "量子资源", "https://cj.lziapi.com/api.php/provide/vod");
    private static final Models.Site DYTT = new Models.Site(
            "dyttzy", "电影天堂资源", "https://caiji.dyttzyapi.com/api.php/provide/vod");
    private static final Models.Site GS = new Models.Site(
            "guangsu", "光速资源", "https://api.guangsuapi.com/api.php/provide/vod");
    private static final Models.Site SUBO = new Models.Site(
            "subo", "素博资源", "https://subocaiji.com/api.php/provide/vod");
    private static final Models.Site JIANPIAN = new Models.Site(
            "jianpian", "荐片线路", "https://api.ztcgi.com");
    private static final String JIANPIAN_IMAGE_BASE = "https://img.cqkgy.com/";

    static final List<Models.Site> SITES = Arrays.asList(JIANPIAN, FFE, LZI, DYTT, GS, SUBO);
    private static final List<Models.Site> PLAYBACK_FALLBACK_SITES =
            Arrays.asList(JIANPIAN, GS, SUBO, DYTT, FFE, LZI);

    private static final class HomePlan {
        final String title;
        final String typeId;
        final List<Models.Site> sites;

        HomePlan(String title, String typeId, Models.Site... sites) {
            this.title = title;
            this.typeId = typeId;
            this.sites = Arrays.asList(sites);
        }
    }

    private static final class ProbeResult {
        final boolean usable;
        final long latencyMs;

        ProbeResult(boolean usable, long latencyMs) {
            this.usable = usable;
            this.latencyMs = latencyMs;
        }
    }

    private static final class ProbeFetch {
        final int status;
        final byte[] body;

        ProbeFetch(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ExecutorService io = Executors.newFixedThreadPool(6);
    private final ExecutorService probes = Executors.newFixedThreadPool(12);
    private final Handler main = new Handler(Looper.getMainLooper());

    void loadHome(Callback<List<Models.HomeSection>> callback) {
        io.execute(() -> {
            List<HomePlan> plans = Arrays.asList(
                    new HomePlan("电影 · 最近更新", null, DYTT),
                    new HomePlan("电影 · 动作片", "6", GS, DYTT, SUBO, FFE),
                    new HomePlan("电视剧 · 国产剧", "13", LZI),
                    new HomePlan("动漫 · 日本", "42", GS),
                    new HomePlan("综艺 · 最近更新", null, SUBO));
            List<Future<Models.HomeSection>> futures = new ArrayList<>();
            for (HomePlan plan : plans) {
                futures.add(io.submit((Callable<Models.HomeSection>) () -> loadSection(plan)));
            }
            List<Models.HomeSection> sections = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    sections.add(futures.get(index).get());
                } catch (Exception error) {
                    sections.add(new Models.HomeSection(plans.get(index).title,
                            new ArrayList<>(), shortMessage(error)));
                }
            }
            deliver(callback, sections, null);
        });
    }

    void search(String keyword, Callback<List<Models.Vod>> callback) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            deliver(callback, new ArrayList<>(), "请输入片名");
            return;
        }
        io.execute(() -> {
            List<Future<List<Models.Vod>>> futures = new ArrayList<>();
            for (Models.Site site : SITES) {
                futures.add(io.submit(() -> {
                    try {
                        return isJianpian(site)
                                ? loadJianpianSearch(query, 24)
                                : loadVodList(site, url(site, "detail", "wd", query, "pg", "1"), 24);
                    } catch (Exception ignored) {
                        return new ArrayList<>();
                    }
                }));
            }
            List<Models.Vod> all = new ArrayList<>();
            for (Future<List<Models.Vod>> future : futures) {
                try {
                    all.addAll(future.get());
                } catch (Exception ignored) {
                    // One source must never block results from the other sources.
                }
            }
            deliver(callback, all, all.isEmpty() ? "没有找到结果，或当前影视源暂时不可用" : null);
        });
    }

    void loadDetail(Models.Vod vod, Callback<Models.Detail> callback) {
        io.execute(() -> {
            try {
                deliver(callback, loadDetailSync(vod), null);
            } catch (Exception error) {
                deliver(callback, null, shortMessage(error));
            }
        });
    }

    void loadPlaybackAlternatives(String title, Callback<List<Models.Detail>> callback) {
        String query = title == null ? "" : title.trim();
        if (query.isEmpty()) {
            deliver(callback, new ArrayList<>(), "片名为空，无法查找备用源");
            return;
        }
        io.execute(() -> {
            List<Future<Models.Detail>> futures = new ArrayList<>();
            for (Models.Site site : PLAYBACK_FALLBACK_SITES) {
                futures.add(io.submit(() -> loadExactDetail(site, query)));
            }
            List<Models.Detail> details = new ArrayList<>();
            for (Future<Models.Detail> future : futures) {
                try {
                    Models.Detail detail = future.get();
                    if (detail != null) details.add(detail);
                } catch (Exception ignored) {
                    // An unavailable source must not block the remaining fallback sources.
                }
            }
            deliver(callback, details, details.isEmpty() ? "其它影视源没有找到同名影片" : null);
        });
    }

    void rankPlaybackLines(Models.Detail detail, String episodeName, int fallbackEpisode,
                           Callback<Models.Detail> callback) {
        if (detail == null || detail.lines.isEmpty()) {
            deliver(callback, detail, "没有可测速的播放线路");
            return;
        }
        io.execute(() -> {
            Map<String, Future<ProbeResult>> unique = new LinkedHashMap<>();
            Map<Models.Line, String> urls = new LinkedHashMap<>();
            for (Models.Line line : detail.lines) {
                line.probeFailed = true;
                line.probeLatencyMs = -1L;
                int episodeIndex = findEpisode(line, episodeName, fallbackEpisode);
                if (episodeIndex < 0 || episodeIndex >= line.episodes.size()) continue;
                String mediaUrl = line.episodes.get(episodeIndex).url;
                if (!isProbeableMedia(mediaUrl)) continue;
                urls.put(line, mediaUrl);
                if (!unique.containsKey(mediaUrl)) {
                    unique.put(mediaUrl, probes.submit(() -> probeMedia(mediaUrl, detail.site.api)));
                }
            }
            int usableCount = 0;
            for (Map.Entry<Models.Line, String> entry : urls.entrySet()) {
                try {
                    ProbeResult result = unique.get(entry.getValue()).get();
                    entry.getKey().probeFailed = !result.usable;
                    entry.getKey().probeLatencyMs = result.latencyMs;
                    if (result.usable) usableCount++;
                } catch (Exception ignored) {
                    entry.getKey().probeFailed = true;
                }
            }
            detail.lines.sort(Comparator
                    .comparing((Models.Line line) -> line.probeFailed)
                    .thenComparingLong(line -> line.probeLatencyMs < 0
                            ? Long.MAX_VALUE : line.probeLatencyMs));
            deliver(callback, detail, usableCount == 0 ? "当前网络没有探测到可用线路" : null);
        });
    }

    void shutdown() {
        io.shutdownNow();
        probes.shutdownNow();
    }

    private Models.HomeSection loadSection(HomePlan plan) {
        for (Models.Site site : plan.sites) {
            try {
                String target = plan.typeId == null
                        ? url(site, "detail", "pg", "1")
                        : url(site, "detail", "t", plan.typeId, "pg", "1");
                List<Models.Vod> items = loadVodList(site, target, 20);
                if (!items.isEmpty()) return new Models.HomeSection(plan.title, items, null);
            } catch (Exception ignored) {
                // Try the next provider for this home section.
            }
        }
        return new Models.HomeSection(plan.title, new ArrayList<>(), "动作片源暂时不可用，请稍后重试");
    }

    private List<Models.Vod> loadVodList(Models.Site site, String target, int limit) throws Exception {
        JSONObject payload = fetchJson(target);
        JSONArray list = payload.optJSONArray("list");
        List<Models.Vod> items = new ArrayList<>();
        if (list == null) return items;
        for (int index = 0; index < Math.min(limit, list.length()); index++) {
            Models.Vod vod = parseVod(site, list.optJSONObject(index));
            if (!vod.id.isEmpty() && !vod.name.isEmpty()) items.add(vod);
        }
        return items;
    }

    private Models.Detail loadDetailSync(Models.Vod vod) throws Exception {
        if (isJianpian(vod.site)) return loadJianpianDetail(vod.id);
        JSONObject payload = fetchJson(url(vod.site, "detail", "ids", vod.id));
        JSONArray list = payload.optJSONArray("list");
        if (list == null || list.length() == 0) throw new IllegalStateException("影视源没有返回详情");
        return parseDetail(vod.site, list.getJSONObject(0));
    }

    private Models.Detail loadExactDetail(Models.Site site, String title) throws Exception {
        List<Models.Vod> matches = isJianpian(site)
                ? loadJianpianSearch(title, 100)
                : loadVodList(site, url(site, "detail", "wd", title, "pg", "1"), 100);
        for (Models.Vod vod : matches) {
            if (title.equalsIgnoreCase(vod.name.trim())) return loadDetailSync(vod);
        }
        return null;
    }

    private static ProbeResult probeMedia(String mediaUrl, String refererApi) {
        long startedAt = System.nanoTime();
        try {
            String lower = mediaUrl.toLowerCase(java.util.Locale.US);
            if (lower.contains(".m3u8")) {
                ProbeFetch manifest = probeFetch(mediaUrl, refererApi, PROBE_MANIFEST_LIMIT, false);
                if (manifest.status < 200 || manifest.status >= 300) return failedProbe(startedAt);
                String playlist = new String(manifest.body, StandardCharsets.UTF_8);
                if (!playlist.contains("#EXTM3U")) return failedProbe(startedAt);
                String currentUrl = mediaUrl;
                String firstMedia = firstPlaylistUri(playlist);
                if (firstMedia.isEmpty()) return failedProbe(startedAt);
                if (firstMedia.toLowerCase(java.util.Locale.US).contains(".m3u8")) {
                    currentUrl = resolveUrl(mediaUrl, firstMedia);
                    manifest = probeFetch(currentUrl, refererApi, PROBE_MANIFEST_LIMIT, false);
                    if (manifest.status < 200 || manifest.status >= 300) return failedProbe(startedAt);
                    playlist = new String(manifest.body, StandardCharsets.UTF_8);
                    firstMedia = firstPlaylistUri(playlist);
                    if (firstMedia.isEmpty()) return failedProbe(startedAt);
                }
                String keyUri = playlistAttribute(playlist, "#EXT-X-KEY", "URI");
                if (!keyUri.isEmpty()) {
                    ProbeFetch key = probeFetch(resolveUrl(currentUrl, keyUri), refererApi, 32, false);
                    if (key.status < 200 || key.status >= 300 || key.body.length < 16) {
                        return failedProbe(startedAt);
                    }
                }
                ProbeFetch segment = probeFetch(resolveUrl(currentUrl, firstMedia), refererApi,
                        PROBE_MEDIA_BYTES, true);
                boolean usable = (segment.status == 200 || segment.status == 206)
                        && segment.body.length >= 1_024;
                return new ProbeResult(usable, elapsedMs(startedAt));
            }
            ProbeFetch media = probeFetch(mediaUrl, refererApi, PROBE_MEDIA_BYTES, true);
            boolean usable = (media.status == 200 || media.status == 206)
                    && media.body.length >= 1_024;
            return new ProbeResult(usable, elapsedMs(startedAt));
        } catch (Exception ignored) {
            return failedProbe(startedAt);
        }
    }

    private static ProbeFetch probeFetch(String target, String refererApi, int limit,
                                         boolean rangeRequest) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(PROBE_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        if (refererApi != null && !refererApi.isEmpty()) {
            try {
                URL api = new URL(refererApi);
                connection.setRequestProperty("Referer", api.getProtocol() + "://" + api.getHost() + "/");
            } catch (Exception ignored) {
                // A malformed provider URL should not prevent probing the media URL.
            }
        }
        if (rangeRequest) connection.setRequestProperty("Range", "bytes=0-" + (limit - 1));
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        byte[] body = input == null ? new byte[0] : readLimited(input, limit);
        connection.disconnect();
        return new ProbeFetch(status, body);
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(limit, 16_384));
        byte[] buffer = new byte[8_192];
        int total = 0;
        try {
            while (total < limit) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (count < 0) break;
                output.write(buffer, 0, count);
                total += count;
            }
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private static String firstPlaylistUri(String playlist) {
        for (String line : playlist.split("\r?\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) return value;
        }
        return "";
    }

    private static String playlistAttribute(String playlist, String tag, String attribute) {
        String marker = attribute + "=\"";
        for (String line : playlist.split("\r?\n")) {
            if (!line.startsWith(tag)) continue;
            int start = line.indexOf(marker);
            if (start < 0) continue;
            start += marker.length();
            int end = line.indexOf('\"', start);
            if (end > start) return line.substring(start, end);
        }
        return "";
    }

    private static String resolveUrl(String base, String child) throws Exception {
        return new URL(new URL(base), child).toString();
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static ProbeResult failedProbe(long startedAt) {
        return new ProbeResult(false, elapsedMs(startedAt));
    }

    private static int findEpisode(Models.Line line, String name, int fallback) {
        String key = episodeKey(name);
        if (name != null && !name.trim().isEmpty()) {
            for (int index = 0; index < line.episodes.size(); index++) {
                String candidate = line.episodes.get(index).name;
                if (name.equals(candidate) || (!key.isEmpty() && key.equals(episodeKey(candidate)))) {
                    return index;
                }
            }
            return -1;
        }
        return line.episodes.isEmpty() ? -1
                : Math.min(Math.max(fallback, 0), line.episodes.size() - 1);
    }

    private static String episodeKey(String name) {
        if (name == null) return "";
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        if (number.find()) {
            try {
                return "episode:" + Integer.parseInt(number.group(1));
            } catch (NumberFormatException ignored) {
                // Fall back to the normalized label.
            }
        }
        return name.trim().toLowerCase(java.util.Locale.US).replaceAll("\\s+", "");
    }

    private static boolean isProbeableMedia(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.US);
        return value.matches("^https?://.+\\.(m3u8|mp4|flv|mkv|ts|mov|avi|webm|m4v|mpd)([?#].*)?$");
    }

    private List<Models.Vod> loadJianpianSearch(String query, int limit) throws Exception {
        JSONObject payload = fetchJson(JIANPIAN.api + "/api/v2/search/videoV2?key="
                + encode(query) + "&page=1");
        JSONArray list = payload.optJSONArray("data");
        List<Models.Vod> items = new ArrayList<>();
        if (list == null) return items;
        for (int index = 0; index < Math.min(limit, list.length()); index++) {
            Models.Vod vod = parseJianpianVod(list.optJSONObject(index));
            if (!vod.id.isEmpty() && !vod.name.isEmpty()) items.add(vod);
        }
        return items;
    }

    private Models.Detail loadJianpianDetail(String id) throws Exception {
        JSONObject payload = fetchJson(JIANPIAN.api + "/api/video/detailv2?id=" + encode(id));
        JSONObject raw = payload.optJSONObject("data");
        if (raw == null) throw new IllegalStateException("荐片线路没有返回详情");
        Models.Vod base = parseJianpianVod(raw);
        Models.Detail detail = new Models.Detail();
        copyVod(base, detail);
        detail.content = raw.optString("description", "").trim();

        JSONArray lines = raw.optJSONArray("source_list_source");
        if (lines == null || lines.length() == 0) {
            lines = raw.optJSONArray("vip_source_list_source");
        }
        if (lines != null) {
            for (int lineIndex = 0; lineIndex < lines.length(); lineIndex++) {
                JSONObject rawLine = lines.optJSONObject(lineIndex);
                if (rawLine == null) continue;
                Models.Line line = new Models.Line(rawLine.optString("name",
                        "线路" + (lineIndex + 1)).trim());
                JSONArray episodes = rawLine.optJSONArray("source_list");
                if (episodes == null) continue;
                for (int episodeIndex = 0; episodeIndex < episodes.length(); episodeIndex++) {
                    JSONObject episode = episodes.optJSONObject(episodeIndex);
                    if (episode == null) continue;
                    String playUrl = episode.optString("url", "").trim();
                    if (playUrl.isEmpty()) continue;
                    String name = episode.optString("source_name", "").trim();
                    if (name.isEmpty()) name = "第" + (episodeIndex + 1) + "集";
                    JSONObject timeData = episode.optJSONObject("time_data");
                    long introSkipMs = timeData == null ? 0L
                            : Math.max(0L, timeData.optLong("titles_duration", 0L)) * 1_000L;
                    line.episodes.add(new Models.Episode(name, playUrl, episodeIndex, introSkipMs));
                }
                if (!line.episodes.isEmpty()) detail.lines.add(line);
            }
        }
        return detail;
    }

    private static Models.Vod parseJianpianVod(JSONObject raw) {
        Models.Vod vod = new Models.Vod();
        if (raw == null) return vod;
        vod.id = String.valueOf(raw.optLong("id", 0L));
        if ("0".equals(vod.id)) vod.id = "";
        vod.name = raw.optString("title", "").trim();
        vod.pic = absolute(raw.optString("thumbnail", ""), JIANPIAN_IMAGE_BASE);
        vod.remarks = raw.optString("mask", "").trim();
        vod.year = firstObjectValue(raw.optJSONArray("years"), "year");
        if (vod.year.isEmpty()) vod.year = raw.optString("year", "").trim();
        vod.area = raw.optString("area", "").trim();
        vod.actor = joinJsonArray(raw.optJSONArray("actors"));
        vod.director = joinJsonArray(raw.optJSONArray("directors"));
        vod.typeName = joinNames(raw.optJSONArray("types"));
        JSONObject topCategory = raw.optJSONObject("top_category");
        if (vod.typeName.isEmpty() && topCategory != null) {
            vod.typeName = value(topCategory, "name", "title");
        }
        Object score = raw.opt("score");
        if (score != null && score != JSONObject.NULL) vod.score = String.valueOf(score);
        vod.site = JIANPIAN;
        return vod;
    }

    private static void copyVod(Models.Vod from, Models.Vod to) {
        to.id = from.id;
        to.name = from.name;
        to.pic = from.pic;
        to.remarks = from.remarks;
        to.year = from.year;
        to.area = from.area;
        to.actor = from.actor;
        to.director = from.director;
        to.typeName = from.typeName;
        to.score = from.score;
        to.site = from.site;
    }

    private static String firstObjectValue(JSONArray values, String key) {
        if (values == null || values.length() == 0) return "";
        JSONObject first = values.optJSONObject(0);
        return first == null ? "" : first.optString(key, "").trim();
    }

    private static String joinJsonArray(JSONArray values) {
        if (values == null) return "";
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            Object item = values.opt(index);
            if (item == null || item == JSONObject.NULL) continue;
            String value;
            if (item instanceof JSONObject) {
                value = value((JSONObject) item, "name", "value");
            } else {
                value = String.valueOf(item).trim();
            }
            if (!value.isEmpty()) parts.add(value);
        }
        return android.text.TextUtils.join("、", parts);
    }

    private static String joinNames(JSONArray values) {
        return joinJsonArray(values);
    }

    private static boolean isJianpian(Models.Site site) {
        return site != null && "jianpian".equals(site.key);
    }

    private static Models.Vod parseVod(Models.Site site, JSONObject raw) {
        Models.Vod vod = new Models.Vod();
        if (raw == null) return vod;
        vod.id = value(raw, "vod_id", "id");
        vod.name = value(raw, "vod_name", "name");
        vod.pic = absolute(value(raw, "vod_pic", "pic"), site.api);
        vod.remarks = value(raw, "vod_remarks", "remarks");
        vod.year = value(raw, "vod_year", "year");
        vod.area = value(raw, "vod_area", "area");
        vod.actor = value(raw, "vod_actor", "actor");
        vod.director = value(raw, "vod_director", "director");
        vod.typeName = value(raw, "type_name", "class");
        vod.score = value(raw, "vod_score", "score");
        vod.site = site;
        return vod;
    }

    private static Models.Detail parseDetail(Models.Site site, JSONObject raw) {
        Models.Vod base = parseVod(site, raw);
        Models.Detail detail = new Models.Detail();
        detail.id = base.id;
        detail.name = base.name;
        detail.pic = base.pic;
        detail.remarks = base.remarks;
        detail.year = base.year;
        detail.area = base.area;
        detail.actor = base.actor;
        detail.director = base.director;
        detail.typeName = base.typeName;
        detail.score = base.score;
        detail.site = site;
        detail.content = raw.optString("vod_content", raw.optString("vod_blurb", ""))
                .replaceAll("<[^>]+>", "").trim();

        String[] froms = raw.optString("vod_play_from", "").split("\\$\\$\\$", -1);
        String[] groups = raw.optString("vod_play_url", "").split("\\$\\$\\$", -1);
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            String name = groupIndex < froms.length && !froms[groupIndex].trim().isEmpty()
                    ? froms[groupIndex].trim() : "线路" + (groupIndex + 1);
            Models.Line line = new Models.Line(name);
            String[] segments = groups[groupIndex].split("#");
            for (String segment : segments) {
                String item = segment.trim();
                if (item.isEmpty()) continue;
                int split = item.indexOf('$');
                String label = split >= 0 ? item.substring(0, split).trim() : "";
                String playUrl = split >= 0 ? item.substring(split + 1).trim() : item;
                if (playUrl.isEmpty()) continue;
                line.episodes.add(new Models.Episode(
                        label.isEmpty() ? "第" + (line.episodes.size() + 1) + "集" : label,
                        playUrl, line.episodes.size()));
            }
            if (!line.episodes.isEmpty()) detail.lines.add(line);
        }
        return detail;
    }

    private static JSONObject fetchJson(String target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) throw new IllegalStateException("HTTP " + status);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        if (status < 200 || status >= 400) throw new IllegalStateException("HTTP " + status);
        return new JSONObject(body.toString().replace("\uFEFF", ""));
    }

    private static String url(Models.Site site, String ac, String... pairs) throws Exception {
        StringBuilder output = new StringBuilder(site.api);
        output.append(site.api.contains("?") ? '&' : '?');
        output.append("ac=").append(encode(ac));
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            output.append('&').append(encode(pairs[index])).append('=').append(encode(pairs[index + 1]));
        }
        return output.toString();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String value(JSONObject raw, String primary, String fallback) {
        Object value = raw.opt(primary);
        if (value == null || value == JSONObject.NULL || String.valueOf(value).trim().isEmpty()) {
            value = raw.opt(fallback);
        }
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static String absolute(String value, String base) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            return new URL(new URL(base), value.trim()).toString();
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private <T> void deliver(Callback<T> callback, T value, String error) {
        main.post(() -> callback.onResult(value, error));
    }

    private static String shortMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }
}
