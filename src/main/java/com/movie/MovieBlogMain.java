package com.movie;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MovieBlogMain {

    // ============ 环境变量，从GitHub Action secrets注入 ============
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String GH_PAT = System.getenv("GH_PAT");
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String FEISHU_WEBHOOK_MOVIE = System.getenv("FEISHU_WEBHOOK_MOVIE");

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final int MAX_OUTPUT_TOKENS = 2800;
    private static final int ARTICLE_MIN_LEN = 1500;
    private static final int ARTICLE_MAX_LEN = 2200;
    // 字数不达标，AI重写最大次数
    private static final int MAX_REWRITE_TIMES = 3;
    private static final int MAX_HISTORY_SIZE = 200;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ====================== 选片片库 ======================
    /**
     * 优先级1：近期上映、有热度院线新片；定期手动增删。
     * 有未使用的就优先从这里抽，贴合当下话题。
     */
    private static final JSONArray HOT_RECENT_POOL = JSONArray.parseArray("""
            [
              {"title":"好东西","year":2024},
              {"title":"浪浪山小妖怪","year":2025},
              {"title":"小丑2","year":2024}
            ]
            """);

    /**
     * 优先级2：高品位品质储备库。
     * 热点池全部写完之后，自动切到这里。
     * 全部是作者向、文艺、现实、镜头语言优秀作品，适合深度影评，拒绝流水线爆米花。
     */
    private static final JSONArray QUALITY_ART_POOL = JSONArray.parseArray("""
            [
              {"title":"阳光普照","year":2019},
              {"title":"春江水暖","year":2019},
              {"title":"一一","year":2000},
              {"title":"步履不停","year":2008},
              {"title":"寂静人生","year":2013},
              {"title":"秋日奏鸣曲","year":1978},
              {"title":"雾中风景","year":1988},
              {"title":"路边野餐","year":2015},
              {"title":"四个春天","year":2017},
              {"title":"大象席地而坐","year":2018},
              {"title":"罗马","year":2018},
              {"title":"一次别离","year":2011},
              {"title":"入殓师","year":2008},
              {"title":"超脱","year":2011},
              {"title":"情书","year":1995},
              {"title":"巴黎夜旅人","year":2022},
              {"title":"百元之恋","year":2013}
            ]
            """);

    public static void main(String[] args) {
        try {
            checkEnv();
            initDir();
            System.out.println("=====电影博主每日影评任务启动=====");

            JSONObject gistData = safeReadGist();
            JSONArray usedMovies = gistData.getJSONArray("used_movies");
            System.out.printf("读取历史已写影片，共%d条%n", usedMovies.size());

            // 选片逻辑：优先近期热点院线片；热点耗尽，切换高品位品质库；严格去重
            JSONObject selectMovie = pickMovie(usedMovies);
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            System.out.printf("今日选中影片：%s (%d)｜选片来源：%s%n", title, year, source);

            // AI生成影评，内置字数不达标自动重写
            String articleContent = generateReviewWithRewrite(title, year, source);
            System.out.printf("生成完成，文章字符长度：%d%n", articleContent.length());

            // 兜底字数校验
            if (articleContent.length() < ARTICLE_MIN_LEN || articleContent.length() > ARTICLE_MAX_LEN) {
                throw new RuntimeException("影评字数校验不通过！实际=" + articleContent.length()
                        + "，要求：" + ARTICLE_MIN_LEN + "-" + ARTICLE_MAX_LEN);
            }

            // 本地落盘输出文件
            saveOutput(title, year, source, articleContent);

            // 写入Gist历史库，防止重复选题
            appendToGistHistory(gistData, title, year, source);

            // 推飞书（失败仅告警，不阻断主流程）
            try {
                sendFeishuCard(title, year, source, articleContent);
                System.out.println("✅飞书卡片推送完成");
            } catch (Exception e) {
                System.err.println("⚠️飞书推送异常：" + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("=====任务全部执行成功=====");
        } catch (Exception e) {
            System.err.println("❌任务执行失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkEnv() {
        if (isBlank(DEEPSEEK_API_KEY)) throw new RuntimeException("缺少 DEEPSEEK_API_KEY");
        if (isBlank(GH_PAT)) throw new RuntimeException("缺少 GH_PAT");
        if (isBlank(GIST_ID)) throw new RuntimeException("缺少 GIST_ID");
        if (isBlank(FEISHU_WEBHOOK_MOVIE)) throw new RuntimeException("缺少 FEISHU_WEBHOOK_MOVIE");
    }

    private static void initDir() throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_DIR));
    }

    /**
     * 选片业务逻辑
     * 1、优先 HOT_RECENT_POOL 近期热点院线，取没有写过的随机一部
     * 2、热点池全部写完，则切换 QUALITY_ART_POOL 高品位品质片库
     * 3、Gist used_movies 做全局去重，绝不重复写同一部电影
     */
    private static JSONObject pickMovie(JSONArray usedMovies) {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            usedKeySet.add(key);
        }

        // 第一步：优先【近期热点院线池】筛选未写过的
        List<JSONObject> hotCandidates = new ArrayList<>();
        for (Object o : HOT_RECENT_POOL) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            if (!usedKeySet.contains(key)) {
                hotCandidates.add(jo);
            }
        }
        if (!hotCandidates.isEmpty()) {
            Random r = new Random();
            JSONObject pick = hotCandidates.get(r.nextInt(hotCandidates.size()));
            pick.put("source", "近期热点院线");
            return pick;
        }

        // 第二步：热点池耗尽，切换【高品位品质储备库】
        List<JSONObject> artCandidates = new ArrayList<>();
        for (Object o : QUALITY_ART_POOL) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            if (!usedKeySet.contains(key)) {
                artCandidates.add(jo);
            }
        }
        if (artCandidates.isEmpty()) {
            throw new RuntimeException("热点池、品质艺术储备库全部耗尽，请手动补充片库！");
        }
        Random r = new Random();
        JSONObject pick = artCandidates.get(r.nextInt(artCandidates.size()));
        pick.put("source", "高品位品质储备库");
        return pick;
    }

    /** 带字数不合格自动多轮重写 */
    private static String generateReviewWithRewrite(String title, int year, String source) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            System.out.printf("------ AI生成第 %d 轮 ------%n", round);
            String content = generateReviewOnce(title, year, source);
            int len = content.length();
            System.out.printf("本轮生成字符数：%d%n", len);
            if (len >= ARTICLE_MIN_LEN && len <= ARTICLE_MAX_LEN) {
                return content;
            }
            System.out.printf("⚠️本轮字数不满足区间[%d‑%d]，准备重写%n", ARTICLE_MIN_LEN, ARTICLE_MAX_LEN);
            sleepMs(3000);
        }
        throw new IOException("经过" + MAX_REWRITE_TIMES + "轮重写，仍然无法生成符合字数要求的影评");
    }

    /** 单次调用DeepSeek‑V4‑Flash生成影评 */
    private static String generateReviewOnce(String title, int year, String source) throws IOException {
        String sysPrompt = String.format("""
                你是一名审美有品位的深度电影博主，拒绝网络套话、拒绝流水线影评，拒绝大段复述剧情。
                今日影片：%s（%d）；选片来源：%s。
                硬性写作约束：
                1.务必写足1500‑2200中文字符，markdown格式，充分展开论述，禁止简略仓促收尾；
                2.第一人称真实观影感受切入；重点分析镜头语言、叙事手法、人物内核、社会隐喻、个人思考；
                3.禁止“封神”“神作”“yyds”这类网络泛滥词汇，不要简单打分评判好坏；
                4.固定行文结构：开篇观影感受引入 → 镜头与人物细读 → 现实延伸思考 → 结尾个人感悟；
                5.输出视角要有品位，挖掘电影背后人文情绪，不要流于表层剧情介绍；严禁抄袭网上现成影评；
                6.直接输出完整正文，不要摘要、不要说明性多余文字。
                """, title, year, source);

        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "deepseek-v4-flash");
        reqBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        reqBody.put("extra_body", JSONObject.of("thinking", false));
        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        reqBody.put("messages", msgs);

        int httpRetry = 2;
        Exception lastErr = null;
        for (int i = 0; i < httpRetry; i++) {
            try {
                RequestBody body = RequestBody.create(reqBody.toString(),
                        MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder()
                        .url(DEEPSEEK_URL)
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                        .post(body)
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    String respStr = resp.body().string();
                    System.out.println("===DeepSeek完整返回报文===\n" + respStr);
                    if (!resp.isSuccessful()) {
                        throw new IOException("DeepSeek http " + resp.code() + " " + respStr);
                    }
                    JSONObject respJson = JSONObject.parseObject(respStr);
                    String raw = respJson.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content");
                    if (raw == null || raw.isBlank()) {
                        throw new IOException("大模型返回content为空！");
                    }
                    return cleanAiRaw(raw);
                }
            } catch (Exception e) {
                lastErr = e;
                sleepMs(2000);
            }
        }
        throw new IOException("HTTP层面调用多次失败", lastErr);
    }

    private static String cleanAiRaw(String raw) {
        String s = raw.trim();
        s = s.replaceAll("^```markdown", "").replaceAll("^```", "").replaceAll("```$", "");
        return s.trim();
    }

    private static void saveOutput(String title, int year, String source, String content) throws IOException {
        Files.write(Paths.get(OUTPUT_DIR, "movie_article.md"), content.getBytes(StandardCharsets.UTF_8));
        JSONObject meta = new JSONObject();
        meta.put("title", title);
        meta.put("year", year);
        meta.put("source", source);
        meta.put("len", content.length());
        meta.put("genTime", new Date());
        Files.write(Paths.get(OUTPUT_DIR, "movie_meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("✅已保存本地 output 稿件文件");
    }

    // ============ Gist读写工具 ============
    private static JSONObject safeReadGist() throws IOException {
        int retry = 2;
        for (int r = 0; r < retry; r++) {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
                        .get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("gist read code:" + resp.code());
                    JSONObject gistObj = JSONObject.parseObject(resp.body().string());
                    JSONObject fileObj = gistObj.getJSONObject("files").getJSONObject(GIST_FILENAME);
                    return JSONObject.parseObject(fileObj.getString("content"));
                }
            } catch (Exception e) {
                sleepMs(1000);
            }
        }
        throw new IOException("读取Gist全部重试失败");
    }

    private static void appendToGistHistory(JSONObject gistData, String title, int year, String source) throws IOException {
        JSONArray used = gistData.getJSONArray("used_movies");
        JSONObject newItem = new JSONObject();
        newItem.put("title", title);
        newItem.put("year", year);
        newItem.put("reason", source);
        newItem.put("gen_time", new Date());
        used.add(newItem);
        // 超过上限裁剪旧记录
        while (used.size() > MAX_HISTORY_SIZE) {
            used.remove(0);
        }

        JSONObject body = new JSONObject();
        JSONObject filesWrap = new JSONObject();
        JSONObject fileItem = new JSONObject();
        fileItem.put("content", JSON.toJSONString(gistData));
        filesWrap.put(GIST_FILENAME, fileItem);
        body.put("files", filesWrap);

        int retry = 2;
        for (int r = 0; r < retry; r++) {
            try {
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
                        .method("PATCH", rb).build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        System.out.println("✅Gist历史库更新成功");
                        return;
                    }
                }
            } catch (Exception ex) {
                sleepMs(1000);
            }
        }
        throw new IOException("写入Gist历史全部重试失败");
    }

    // ============ 飞书卡片推送 ============
    private static void sendFeishuCard(String title, int year, String source, String content) throws IOException {
        int previewMax = 450;
        String preview = content.length() > previewMax
                ? content.substring(0, previewMax) + "\n……\n> 📄完整稿件请查看Action产物 movie_article.md"
                : content;

        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONObject cardBody = new JSONObject();
        cardBody.put("wide_screen_mode", true);
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md",
                "content", String.format("**🎬今日影评：%s（%d）**\n选片来源：%s", title, year, source))));
        elements.add(JSONObject.of("tag", "hr"));
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", preview)));
        cardBody.put("elements", elements);
        card.put("card", cardBody);

        RequestBody rb = RequestBody.create(card.toString(), MediaType.parse("application/json;charset=utf-8"));
        Request req = new Request.Builder()
                .url(FEISHU_WEBHOOK_MOVIE)
                .post(rb)
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("飞书推送http失败:"+resp.code());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleepMs(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
