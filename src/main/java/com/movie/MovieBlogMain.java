package com.movie;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MovieBlogMain {
    // ============ 环境变量 ============
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String GH_PAT = System.getenv("GH_PAT");
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String FEISHU_WEBHOOK_MOVIE = System.getenv("FEISHU_WEBHOOK_MOVIE");
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";

    // 字数区间：1400-1800 稳定适配AI输出
    private static final int MAX_OUTPUT_TOKENS = 3500;
    private static final int ARTICLE_MIN_LEN = 1400;
    private static final int ARTICLE_MAX_LEN = 1800;
    private static final int MAX_REWRITE_TIMES = 3;
    private static final int MAX_HISTORY_SIZE = 500;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    // 经典影片兜底TAG池（无固定片名，AI全网自选，永不枯竭）
    private static final String[] CLASSIC_TAGS = {
            "现实高分经典", "人性传世经典", "家庭治愈经典", "小众高分佳作",
            "情感深度经典", "文艺叙事经典", "国产优质佳作", "纪实温情经典",
            "现实深度佳作", "奥斯卡获奖经典", "人性博弈经典", "治愈系高分经典",
            "青春爱情经典", "逆袭励志经典", "年代传世佳作", "小众文艺热片"
    };

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static void main(String[] args) {
        try {
            checkEnv();
            initDir();
            System.out.println("=====【终极稳跑版】容错增强+稳定扩写任务启动=====");

            // 自动季节档期识别
            String currentSeason = getCurrentSeason();
            String currentFileStage = getCurrentMovieFileStage();
            System.out.printf("✅系统自动识别当前季节：%s｜当前影视档期：%s%n", currentSeason, currentFileStage);

            JSONObject gistData = safeReadGist();
            JSONArray usedMovies = gistData.getJSONArray("used_movies");
            System.out.printf("读取全局去重片库：%d条%n", usedMovies.size());

            // 容错增强选片
            JSONObject selectMovie = autoPickMovieByAI(usedMovies, currentSeason, currentFileStage);
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            String movieTag = selectMovie.getString("tag");
            String selectReason = selectMovie.getString("reason");

            System.out.printf("✅今日AI选片：%s(%d)｜流量类型：%s｜影片标签：%s｜选片依据：%s%n", title, year, source, movieTag, selectReason);

            // 梯度稳定扩写影评
            String articleContent = generateReviewWithRewrite(title, year, source, movieTag, selectReason);
            int articleLen = articleContent.length();
            System.out.printf("📝影评生成完成，字数：%d%n", articleLen);

            // 字数校验
            if (articleLen < ARTICLE_MIN_LEN || articleLen > ARTICLE_MAX_LEN) {
                throw new RuntimeException("字数不达标：" + articleLen);
            }

            // 保存&去重
            saveOutput(title, year, source, movieTag, selectReason, articleContent);
            appendToGistHistory(gistData, title, year, source, movieTag, selectReason);

            // 飞书推送容错
            try {
                sendFeishuFullCardArticle(title, year, source, movieTag, selectReason, articleContent, articleLen);
                System.out.println("✅全文推送成功！任务正常完成");
            } catch (Exception e) {
                System.err.println("⚠️飞书推送异常：" + e.getMessage());
            }

            System.out.println("=====今日全自动影评任务圆满完成=====");
        } catch (Exception e) {
            System.err.println("❌任务失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // 自动获取当前季节
    private static String getCurrentSeason() {
        int month = LocalDate.now().getMonthValue();
        if (month >= 3 && month <= 5) return "春季";
        if (month >= 6 && month <= 8) return "夏季";
        if (month >= 9 && month <= 11) return "秋季";
        return "冬季";
    }

    // 自动匹配全年影视档期
    private static String getCurrentMovieFileStage() {
        int month = LocalDate.now().getMonthValue();
        if (month == 1 || month == 2) return "春节贺岁档";
        if (month >= 3 && month <= 5) return "春季常规档期";
        if (month >= 6 && month <= 8) return "暑期黄金档期";
        if (month == 9 || month == 10) return "国庆黄金档期";
        return "年末贺岁预热档期";
    }

    // 环境变量校验
    private static void checkEnv() {
        if (isBlank(DEEPSEEK_API_KEY)) throw new RuntimeException("缺少 DEEPSEEK_API_KEY");
        if (isBlank(GH_PAT)) throw new RuntimeException("缺少 GH_PAT");
        if (isBlank(GIST_ID)) throw new RuntimeException("缺少 GIST_ID");
        if (isBlank(FEISHU_WEBHOOK_MOVIE)) throw new RuntimeException("缺少 FEISHU_WEBHOOK_MOVIE");
    }

    // 初始化输出目录
    private static void initDir() throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_DIR));
    }

    // 【彻底修复空指针】多层判空+接口重试+异常兜底，杜绝aiResult=null崩溃
    private static JSONObject autoPickMovieByAI(JSONArray usedMovies, String season, String fileStage) throws IOException {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            usedKeySet.add(jo.getString("title") + "|" + jo.getIntValue("year"));
        }
        String tagList = String.join("、", CLASSIC_TAGS);

        String aiPickPrompt = "你是头条影视自媒体流量选片专家，当前时间：" + season + "，当前影视档期：" + fileStage + "。"
                + "一、热点影片筛选标准（满足任意2条即为有效流量热片）："
                + "1.近2个月院线/网络上新影片；2.全网有热搜、话题、高讨论度；3.头条/抖音有稳定用户搜索流量；4.各大影视热度榜单上榜。"
                + "优先筛选1部未创作过的近期高流量热片。"
                + "二、若无符合条件的热点新片，执行经典兜底规则："
                + "从全网高分、长效流量、适合头条影评二次传播的优质经典电影中，随机挑选一部，严格匹配以下标签品类：" + tagList + "。"
                + "三、硬性约束：绝对禁止选择以下已创作过的影片，永久去重：" + usedKeySet
                + "四、返回规范：严格输出纯JSON，无多余文字、无解释、无markdown格式，字段必填："
                + "{\"title\":\"影片名\",\"year\":\"上映年份\",\"tag\":\"匹配上述经典标签/热点影片标签\",\"reason\":\"选片依据\",\"source\":\"近期热点流量影片/AI标签兜底经典长尾影片\"}";

        // 多次重试+严格判空
        JSONObject aiResult = null;
        for (int i = 0; i < 3; i++) {
            aiResult = callAIPickMovie(aiPickPrompt);
            // 有效结果直接放行
            if (aiResult != null && !isBlank(aiResult.getString("title")) && aiResult.getIntValue("year") > 0) {
                return aiResult;
            }
            System.out.printf("⚠️第%d轮AI选片返回空/异常，重试中...%n", i + 1);
            sleepMs(3000);
        }

        // 终极兜底：接口全部异常时，本地固定高分经典兜底，彻底杜绝任务崩溃
        System.out.println("🔥AI接口重试失败，触发本地终极影片兜底机制");
        JSONObject fallbackMovie = new JSONObject();
        fallbackMovie.put("title", "活着");
        fallbackMovie.put("year", 1994);
        fallbackMovie.put("tag", "人性传世经典、现实高分经典");
        fallbackMovie.put("reason", "全网顶流高分传世经典，长效搜索流量极高，适配头条长期收录");
        fallbackMovie.put("source", "AI兜底应急经典长尾影片");
        return fallbackMovie;
    }

    // AI选片专用接口调用（增强容错）
    private static JSONObject callAIPickMovie(String prompt) {
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("model", "deepseek-v4-flash");
            reqBody.put("max_tokens", 1024);
            reqBody.put("temperature", 0.8);
            reqBody.put("top_p", 0.95);

            JSONArray msgs = new JSONArray();
            msgs.add(JSONObject.of("role", "system", "content", "你是专业影视流量分析师，严格按标签和热度规则选片，仅返回标准JSON数据，无任何多余内容。"));
            msgs.add(JSONObject.of("role", "user", "content", prompt));
            reqBody.put("messages", msgs);

            RequestBody body = RequestBody.create(reqBody.toString(), MediaType.parse("application/json;charset=utf-8"));
            Request req = new Request.Builder()
                    .url(DEEPSEEK_URL)
                    .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                    .post(body)
                    .build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) return null;
                String resStr = resp.body().string();
                if (isBlank(resStr)) return null;
                JSONObject resJson = JSONObject.parseObject(resStr);
                JSONArray choices = resJson.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) return null;
                String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
                content = content.replaceAll("^```json|^```|```$", "").trim();
                if (isBlank(content)) return null;
                return JSONObject.parseObject(content);
            }
        } catch (Exception e) {
            System.err.println("AI选片接口调用异常：" + e.getMessage());
            return null;
        }
    }

    // 多轮梯度扩写+防空内容+防清空，稳定输出1400-1800字
    private static String generateReviewWithRewrite(String title, int year, String source, String tag, String reason) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            String content = generateReviewOnce(title, year, source, tag, reason, round);
            // 拦截空内容
            if (content == null || content.isBlank()) {
                System.out.printf("⚠️第%d轮生成内容为空，重新生成%n", round);
                sleepMs(3000);
                continue;
            }
            int len = content.length();
            if (len >= ARTICLE_MIN_LEN && len <= ARTICLE_MAX_LEN) {
                return content;
            }
            System.out.printf("⚠️字数不达标（%d字），第%d轮重新扩写优化%n", len, round);
            sleepMs(3000);
        }
        throw new IOException("多轮重写后仍未达到1400-1800字流量标准");
    }

    // 梯度扩写提示词，逐级加强、只扩不删、杜绝清空
    private static String generateReviewOnce(String title, int year, String source, String tag, String reason, int rewriteRound) throws IOException {
        String extraRule = "";
        if (rewriteRound == 1) {
            extraRule = "完整深度创作，内容饱满详实，务必达到1400字以上，禁止简短梗概、禁止敷衍。";
        } else if (rewriteRound == 2) {
            extraRule = "大幅细化扩写，补充人物细节、剧情隐喻、社会背景、个人感悟、现实延伸，稳固字数，文风保持统一。";
        } else {
            extraRule = "终极精细化扩容，多角度补充思辨、观众共鸣、影片价值解读，严格锁定1400-1800字，禁止清空、禁止重写，仅补充细化。";
        }

        String flowTip = source.contains("热点")
                ? "本片为当下全网热门新片，贴合当期档期热点、大众情绪，适配短期爆发流量。"
                : "本片为高分经典影片，深挖内核与时代共鸣，适配头条长期搜索长尾流量。";

        String sysPrompt = "你是头条小众独立影评博主，固定【温柔细腻、清醒思辨、真诚有温度】的专属个人风格，无模板、无流水线、无烂大街话术。"
                + "评析影片：" + title + "(" + year + ")｜流量属性：" + source + "｜影片标签：" + tag + "｜选片依据：" + reason + flowTip
                + "写作硬性规则："
                + "1、纯正文输出1400-1800字符，无标题、打分、摘要、小结、多余符号；"
                + "2、短段落排版，适配手机竖屏阅读，提升完读率；"
                + "3、极简剧情铺垫，95%内容为个人深度解读、人性剖析、现实共鸣；"
                + "4、观点小众独特，强化个人IP辨识度；"
                + "5、只允许扩写细化，禁止清空重写、禁止大幅删减；"
                + "6、文风真诚克制、有思考、有态度，内容扎实饱满。" + extraRule;

        JSONObject req = new JSONObject();
        req.put("model", "deepseek-v4-flash");
        req.put("max_tokens", MAX_OUTPUT_TOKENS);
        req.put("temperature", 0.9);
        req.put("top_p", 0.95);
        req.put("extra_body", JSONObject.of("thinking", false));

        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        msgs.add(JSONObject.of("role", "user", "content", "输出一篇风格统一、深度饱满、字数达标、适配头条长效流量的个人专属影评正文。"));
        req.put("messages", msgs);

        int retry = 2;
        for (int i = 0; i < retry; i++) {
            try {
                RequestBody body = RequestBody.create(req.toString(), MediaType.parse("application/json;charset=utf-8"));
                Response resp = HTTP_CLIENT.newCall(new Request.Builder().url(DEEPSEEK_URL)
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY).post(body).build()).execute();
                String raw = JSONObject.parseObject(resp.body().string())
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
                return cleanAiContent(raw);
            } catch (Exception e) {
                sleepMs(2000);
            }
        }
        throw new IOException("影评生成接口请求失败");
    }

    // AI内容清洗
    private static String cleanAiContent(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("^```markdown|^```|```$", "").replace("\r\n", "\n");
        return s.trim();
    }

    // 本地保存稿件与元数据
    private static void saveOutput(String title, int year, String source, String tag, String reason, String content) throws IOException {
        Files.write(Paths.get(OUTPUT_DIR, "movie_article.md"), content.getBytes(StandardCharsets.UTF_8));
        JSONObject meta = new JSONObject();
        meta.put("title", title);
        meta.put("year", year);
        meta.put("flow_source", source);
        meta.put("movie_tag", tag);
        meta.put("select_reason", reason);
        meta.put("len", content.length());
        meta.put("gen_time", new Date());
        Files.write(Paths.get(OUTPUT_DIR, "movie_meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
    }

    // 读取GIST去重库
    private static JSONObject safeReadGist() throws IOException {
        int retry = 2;
        for (int r = 0; r < retry; r++) {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT).get().build();
                Response resp = HTTP_CLIENT.newCall(req).execute();
                JSONObject gist = JSONObject.parseObject(resp.body().string());
                return JSONObject.parseObject(gist.getJSONObject("files").getJSONObject(GIST_FILENAME).getString("content"));
            } catch (Exception e) {
                sleepMs(1000);
            }
        }
        throw new IOException("读取GIST历史库失败");
    }

    // 更新GIST去重记录
    private static void appendToGistHistory(JSONObject gistData, String title, int year, String source, String tag, String reason) throws IOException {
        JSONArray used = gistData.getJSONArray("used_movies");
        JSONObject item = new JSONObject();
        item.put("title", title);
        item.put("year", year);
        item.put("source", source);
        item.put("tag", tag);
        item.put("reason", reason);
        item.put("gen_time", new Date());
        used.add(item);
        while (used.size() > MAX_HISTORY_SIZE) used.remove(0);

        JSONObject body = new JSONObject();
        JSONObject file = new JSONObject();
        file.put("content", JSON.toJSONString(gistData));
        JSONObject files = new JSONObject();
        files.put(GIST_FILENAME, file);
        body.put("files", files);

        int retry = 2;
        for (int r = 0; r < retry; r++) {
            try {
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
                        .method("PATCH", rb).build();
                if (HTTP_CLIENT.newCall(req).execute().isSuccessful()) return;
            } catch (Exception e) {
                sleepMs(1000);
            }
        }
        throw new IOException("写入GIST历史库失败");
    }

    // 飞书分片推送
    private static void sendFeishuFullCardArticle(String title, int year, String source, String tag, String reason, String content, int len) throws IOException {
        String header = String.format("🎬AI标签智能选片·头条长效影评\n影片：%s（%d）\n流量类型：%s\n影片标签：%s\n选片依据：%s\n文章字数：%d\n——————————\n",
                title, year, source, tag, reason, len);
        sendFeishuChunk(header);
        for (int i = 0; i < content.length(); i += 1200) {
            int end = Math.min(i + 1200, content.length());
            sendFeishuChunk(content.substring(i, end));
            sleepMs(300);
        }
    }

    private static void sendFeishuChunk(String text) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("msg_type", "interactive");
        JSONObject card = new JSONObject();
        card.put("wide_screen_mode", true);
        JSONArray ele = new JSONArray();
        ele.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", text)));
        card.put("elements", ele);
        payload.put("card", card);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json;charset=utf-8"));
        HTTP_CLIENT.newCall(new Request.Builder().url(FEISHU_WEBHOOK_MOVIE).post(body).build()).execute();
    }

    // 工具方法
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleepMs(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); } catch (Exception ignored) {}
    }
}
