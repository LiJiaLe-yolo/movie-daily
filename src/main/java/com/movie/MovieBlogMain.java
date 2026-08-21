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
    // 放宽上限，v4‑flash中文输出字符膨胀
    private static final int ARTICLE_MIN_LEN = 1500;
    private static final int ARTICLE_MAX_LEN = 2200;
    private static final int MAX_HISTORY_SIZE = 200;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ====================== 1、候选片池配置 ======================
    /** 第一优先级：近期热点片池（你定期手动维护，放入近期上映/话题电影） */
    private static final JSONArray HOT_POOL = JSONArray.parseArray("""
            [
              {"title":"好东西","year":2024},
              {"title":"浪浪山小妖怪","year":2025},
              {"title":"小丑2","year":2024}
            ]
            """);

    /** 第二优先级：备用精品兜底池，豆瓣高分小众艺术电影，热点耗尽自动切这里 */
    private static final JSONArray BACKUP_POOL = JSONArray.parseArray("""
            [
              {"title":"寂静人生","year":2013},
              {"title":"秋日奏鸣曲","year":1978},
              {"title":"雾中风景","year":1988},
              {"title":"安纳托利亚往事","year":2011},
              {"title":"四个春天","year":2017},
              {"title":"大象席地而坐","year":2018},
              {"title":"罗马","year":2018},
              {"title":"利维坦","year":2014},
              {"title":"一次别离","year":2011},
              {"title":"步履不停","year":2008}
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

            // 选片：优先热点池；热点全部命中历史自动降级备用池
            JSONObject selectMovie = pickMovie(usedMovies);
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            System.out.printf("今日选中影片：%s (%d)｜选片来源：%s%n", title, year, source);

            // AI生成影评
            String articleContent = generateReview(title, year, source);
            System.out.printf("生成完成，文章字符长度：%d%n", articleContent.length());

            // 强制字数校验
            if (articleContent.length() < ARTICLE_MIN_LEN || articleContent.length() > ARTICLE_MAX_LEN) {
                throw new RuntimeException("影评字数校验不通过！实际=" + articleContent.length()
                        + "，要求：" + ARTICLE_MIN_LEN + "-" + ARTICLE_MAX_LEN);
            }

            // 本地落盘输出文件
            saveOutput(title, year, source, articleContent);

            // 业务全部成功之后，写入Gist历史库，防止下次重复选题
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

    /** 选片逻辑：先热点池过滤历史，无可用则切备用池 */
    private static JSONObject pickMovie(JSONArray usedMovies) {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            usedKeySet.add(key);
        }

        // 优先热点池筛选未使用的
        List<JSONObject> hotCandidates = new ArrayList<>();
        for (Object o : HOT_POOL) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            if (!usedKeySet.contains(key)) {
                hotCandidates.add(jo);
            }
        }
        if (!hotCandidates.isEmpty()) {
            Random r = new Random();
            JSONObject pick = hotCandidates.get(r.nextInt(hotCandidates.size()));
            pick.put("source", "近期热点影片");
            return pick;
        }

        // 热点全部用过，降级备用精品池
        List<JSONObject> backupCandidates = new ArrayList<>();
        for (Object o : BACKUP_POOL) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            if (!usedKeySet.contains(key)) {
                backupCandidates.add(jo);
            }
        }
        if (backupCandidates.isEmpty()) {
            throw new RuntimeException("热点池、备用池全部耗尽，请补充片库！");
        }
        Random r = new Random();
        JSONObject pick = backupCandidates.get(r.nextInt(backupCandidates.size()));
        pick.put("source", "经典备用精品池");
        return pick;
    }

    /** 调用DeepSeek‑V4‑Flash生成影评，关闭思考模式 */
    private static String generateReview(String title, int year, String source) throws IOException {
        String sysPrompt = String.format("""
                你是一名独立深度电影博主，有稳定个人表达，拒绝网络影评套话，拒绝大段复述剧情。
                今日影片：%s（%d）；选片来源：%s。
                硬性写作约束：
                1.全文字符严格控制在1500‑1800字符，输出markdown格式；禁止过度延展、不要写多余段落；
                2.以第一人称观影感受切入，少剧透；重点写镜头语言、人物内核、社会隐喻、个人思考；
                3.禁止“封神”“神作”“yyds”这类网络泛滥词汇；不要简单打分评价好坏；
                4.文章结构：开篇观影感受引入 → 镜头/人物细读 → 现实延伸思考 → 结尾个人感悟；
                5.严禁复制网上现成影评，全部使用自己语言重新组织，要有独特视角；
                6.直接输出完整正文，不要摘要、不要说明性多余文字。
                """, title, year, source);

        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "deepseek-v4-flash");
        reqBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        // 关闭思考模式，避免输出reasoning思考过程文本
        reqBody.put("extra_body", JSONObject.of("thinking", false));
        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        reqBody.put("messages", msgs);

        int retry = 2;
        Exception lastErr = null;
        for (int i = 0; i < retry; i++) {
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
        throw new IOException("大模型调用多次失败", lastErr);
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
