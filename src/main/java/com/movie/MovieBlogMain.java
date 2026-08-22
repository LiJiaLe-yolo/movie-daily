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

    // 【修复】下调最低字数门槛：1400-1800，适配AI实际输出能力，杜绝频繁报错
    private static final int MAX_OUTPUT_TOKENS = 3500;
    private static final int ARTICLE_MIN_LEN = 1400;
    private static final int ARTICLE_MAX_LEN = 1800;
    private static final int MAX_REWRITE_TIMES = 3;
    private static final int MAX_HISTORY_SIZE = 500;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    // 经典影片兜底TAG池（无固定片名，AI按标签全网自选，永久写不完）
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
            System.out.println("=====【修复版】1400-1800字数稳定扩写任务启动=====");

            // 自动获取当前季节+档期（全年动态适配）
            String currentSeason = getCurrentSeason();
            String currentFileStage = getCurrentMovieFileStage();
            System.out.printf("✅系统自动识别当前季节：%s｜当前影视档期：%s%n", currentSeason, currentFileStage);

            JSONObject gistData = safeReadGist();
            JSONArray usedMovies = gistData.getJSONArray("used_movies");
            System.out.printf("读取全局去重片库：%d条%n", usedMovies.size());

            // 核心选片：热片优先 + 标签式AI经典兜底
            JSONObject selectMovie = autoPickMovieByAI(usedMovies, currentSeason, currentFileStage);
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            String movieTag = selectMovie.getString("tag");
            String selectReason = selectMovie.getString("reason");

            System.out.printf("✅今日AI选片：%s(%d)｜流量类型：%s｜影片标签：%s｜选片依据：%s%n", title, year, source, movieTag, selectReason);

            // 生成影评+多轮稳扩写
            String articleContent = generateReviewWithRewrite(title, year, source, movieTag, selectReason);
            int articleLen = articleContent.length();
            System.out.printf("📝影评生成完成，字数：%d%n", articleLen);

            // 字数合规校验
            if (articleLen < ARTICLE_MIN_LEN || articleLen > ARTICLE_MAX_LEN) {
                throw new RuntimeException("字数不达标：" + articleLen);
            }

            // 本地保存+全局去重记录
            saveOutput(title, year, source, movieTag, selectReason, articleContent);
            appendToGistHistory(gistData, title, year, source, movieTag, selectReason);

            // 飞书全文推送
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

    // 全自动选片：热点优先 + 标签兜底无固定片名 + 全局永久去重
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

        JSONObject aiResult = callAIPickMovie(aiPickPrompt);
        String hotTitle = aiResult.getString("title");
        if (isBlank(hotTitle) || aiResult.getIntValue("year") <= 0) {
            throw new IOException("AI选片结果异常，请重新触发选片");
        }
        return aiResult;
    }

    // AI选片专用接口调用
    private static JSONObject callAIPickMovie(String prompt) throws IOException {
        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "deepseek-v4-flash");
        reqBody.put("max_tokens", 1024);
        reqBody.put("temperature", 0.8);
        reqBody.put("top_p", 0.95);

        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", "你是专业影视流量分析师，严格按标签和热度规则选片，仅返回标准JSON数据，无任何多余内容。"));
        msgs.add(JSONObject.of("role", "user", "content", prompt));
        reqBody.put("messages", msgs);

        int retry = 3;
        Exception err = null;
        for (int i = 0; i < retry; i++) {
            try {
                RequestBody body = RequestBody.create(reqBody.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url(DEEPSEEK_URL)
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                        .post(body)
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("接口请求异常");
                    String content = JSONObject.parseObject(resp.body().string())
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content");
                    content = content.replaceAll("^```json|^```|```$", "").trim();
                    return JSONObject.parseObject(content);
                }
            } catch (Exception e) {
                err = e;
                sleepMs(2000);
            }
        }
        throw new IOException("AI选片接口多次调用失败", err);
    }

    // 【重点优化】多轮重写容错+防空内容+梯度强制扩写，彻底解决487/1203/0字报错问题
    private static String generateReviewWithRewrite(String title, int year, String source, String tag, String reason) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            String content = generateReviewOnce(title, year, source, tag, reason, round);
            // 兜底：杜绝第三轮0空内容
            if (content == null || content.isBlank()) {
                System.out.printf("⚠️第%d轮生成内容为空，重新生成%n", round);
                sleepMs(3000);
                continue;
            }
            int len = content.length();
            // 宽松达标：只要≥1400直接放行，不再卡死1600
            if (len >= ARTICLE_MIN_LEN && len <= ARTICLE_MAX_LEN) {
                return content;
            }
            System.out.printf("⚠️字数不达标（%d字），第%d轮重新扩写优化%n", len, round);
            sleepMs(3000);
        }
        throw new IOException("多轮重写后仍未达到1400-1800字流量标准");
    }

    // 【重点优化】梯度扩写提示词，每轮递增扩写力度，杜绝字数上不去、内容清空
    private static String generateReviewOnce(String title, int year, String source, String tag, String reason, int rewriteRound) throws IOException {
        String extraRule = "";
        // 梯度扩写：逐级加强，稳增字数，不清空内容
        if (rewriteRound == 1) {
            extraRule = "正常完整创作，保证内容饱满，字数务必达到1400字以上，不允许简短敷衍、不允许只写梗概。";
        } else if (rewriteRound == 2) {
            extraRule = "大幅扩写全文，增加人物细节、剧情细节、社会背景、个人感悟、现实延伸，填充足量内容，稳固1400字以上，文风不变。";
        } else {
            extraRule = "终极补字：在原有内容基础上精细化扩容，补充多角度思辨、观众共鸣、影片价值解读，严格控制在1400-1800字，禁止清空重写。";
        }

        String flowTip = source.contains("热点")
                ? "本片为当下全网热门新片，写作贴合当期影视档期、全网热议话题、大众情绪共鸣，适配头条短期爆发推荐流量。"
                : "本片为标签匹配优质经典影片，侧重深挖传世内核、人性价值、长久时代共鸣，适配头条全年搜索长尾流量，可长期收录获流。";

        String sysPrompt = "你是头条小众独立影评博主，固定【温柔细腻、清醒思辨、真诚有温度】的专属个人风格，无模板化、无流水线、无烂大街话术，账号辨识度极强。"
                + "评析影片：" + title + "(" + year + ")｜流量属性：" + source + "｜影片标签：" + tag + "｜选片依据：" + reason + flowTip
                + "严格遵守头条长效流量写作规则："
                + "1、字数严格锁定1400-1800字符，仅输出纯正文，无标题、无打分、无摘要、无小结、无多余符号；"
                + "2、适配头条竖屏阅读，短段落错落分布，每段2-4行，降低阅读门槛、提升完读率；"
                + "3、极简剧情铺垫，一句话概括核心故事，95%内容为个人独立深度解读、人性剖析、现实共鸣、价值思考；"
                + "4、观点小众独特，避开全网通用影评角度，强化个人IP；"
                + "5、禁止清空重写、禁止大幅删减，只允许扩写、细化、补充内容；"
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

    // AI内容格式清洗
    private static String cleanAiContent(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("^```markdown|^```|```$", "").replace("\r\n", "\n");
        return s.trim();
    }

    // 本地保存影评稿件与完整元数据
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

    // 读取GIST全局去重历史库
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

    // 更新GIST去重历史，永久不重复
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

    // 飞书全文卡片推送
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

    // 飞书消息分片推送工具方法
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
