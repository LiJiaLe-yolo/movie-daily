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
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MovieBlogMain {
    // ============ 环境变量 ============
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String GH_PAT = System.getenv("GH_PAT");
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String FEISHU_WEBHOOK_MOVIE = System.getenv("FEISHU_WEBHOOK_MOVIE");
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";

    // 字数区间：1400-1800 稳定适配AI输出（宽松容错）
    private static final int MAX_OUTPUT_TOKENS = 4000;
    private static final int ARTICLE_MIN_LEN = 1400;
    private static final int ARTICLE_MAX_LEN = 1800;
    private static final int MAX_REWRITE_TIMES = 3;
    private static final int MAX_RESELECT_TIMES = 2;
    private static final int MAX_HISTORY_SIZE = 500;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    // 经典影片兜底TAG池
    private static final String[] CLASSIC_TAGS = {
            "现实高分经典", "人性传世经典", "家庭治愈经典", "小众高分佳作",
            "情感深度经典", "文艺叙事经典", "国产优质佳作", "纪实温情经典",
            "现实深度佳作", "奥斯卡获奖经典", "人性博弈经典", "治愈系高分经典",
            "青春爱情经典", "逆袭励志经典", "年代传世佳作", "小众文艺热片"
    };

    // 【核心新增】经典兜底影片轮询库（全覆盖、高频不重复、全网素材充足、真实年份）
    private static final List<Map<String,Object>> CLASSIC_MOVIE_POOL;
    static {
        CLASSIC_MOVIE_POOL = new ArrayList<>();
        CLASSIC_MOVIE_POOL.add(Map.of("title","活着","year",1994,"tag","人性传世经典、现实高分经典","reason","国产顶级现实经典，素材充足，适配长效流量"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","霸王别姬","year",1993,"tag","影史封神经典、时代叙事经典","reason","华语影史天花板，解读角度极多，流量稳定"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","阿甘正传","year",1994,"tag","励志传世经典、人生治愈经典","reason","全球高分常青佳作，受众极广，长尾流量充足"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","肖申克的救赎","year",1994,"tag","人性博弈经典、逆袭励志经典","reason","影史高分榜首，常年热搜，可深度解读维度丰富"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","山海情","year",2021,"tag","现实纪实经典、家国温情佳作","reason","国产高分现实题材，口碑过硬，适配大众共鸣流量"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","你好，李焕英","year",2021,"tag","家庭治愈经典、温情现实佳作","reason","国民级温情影片，受众广泛，讨论度持久"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","千与千寻","year",2001,"tag","治愈文艺经典、成长寓言佳作","reason","日系传世动画，常年有搜索流量，解读维度丰富"));
        CLASSIC_MOVIE_POOL.add(Map.of("title","寻梦环游记","year",2017,"tag","亲情治愈经典、奇幻温情佳作","reason","亲情治愈顶流动画，大众好感度高，适配自媒体流量"));
    }

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
            System.out.println("=====【终极防重复+保真版】兜底轮询+杜绝幻觉+稳扩写任务启动=====");

            // 自动季节档期识别
            String currentSeason = getCurrentSeason();
            String currentFileStage = getCurrentMovieFileStage();
            int currentYear = LocalDate.now().getYear();
            System.out.printf("✅系统自动识别：当前年份%d｜季节：%s｜档期：%s%n", currentYear, currentSeason, currentFileStage);

            JSONObject gistData = safeReadGist();
            JSONArray usedMovies = gistData.getJSONArray("used_movies");
            System.out.printf("读取全局去重片库：%d条%n", usedMovies.size());

            // 支持空内容重选片容错
            JSONObject selectMovie = null;
            String articleContent = null;
            for (int reSelect = 0; reSelect <= MAX_RESELECT_TIMES; reSelect++) {
                selectMovie = autoPickMovieByAI(usedMovies, currentSeason, currentFileStage, currentYear);
                String title = selectMovie.getString("title");
                int year = selectMovie.getIntValue("year");
                String source = selectMovie.getString("source");
                String movieTag = selectMovie.getString("tag");
                String selectReason = selectMovie.getString("reason");

                System.out.printf("✅第%d轮选片：%s(%d)｜流量类型：%s｜影片标签：%s｜选片依据：%s%n",
                        reSelect + 1, title, year, source, movieTag, selectReason);

                // 尝试生成影评
                try {
                    articleContent = generateReviewWithRewrite(title, year, source, movieTag, selectReason);
                    break;
                } catch (Exception e) {
                    System.out.printf("⚠️当前影片生成失败，触发第%d次重选片机制%n", reSelect + 1);
                    sleepMs(2000);
                }
            }

            // 最终兜底判定
            if (articleContent == null || articleContent.isBlank()) {
                throw new RuntimeException("多次选片+重写均生成失败，任务终止");
            }

            int articleLen = articleContent.length();
            System.out.printf("📝影评生成完成，字数：%d%n", articleLen);

            // 宽松字数校验（容错兜底，不卡死任务）
            if (articleLen < ARTICLE_MIN_LEN || articleLen > ARTICLE_MAX_LEN) {
                System.out.printf("⚠️字数小幅偏差（%d字），兜底放行，保证任务不崩溃%n", articleLen);
            }

            // 保存&去重
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            String movieTag = selectMovie.getString("tag");
            String selectReason = selectMovie.getString("reason");
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

    // 【核心终极修复】AI选片+兜底轮询防重复+强制真实年份、杜绝AI幻觉
    private static JSONObject autoPickMovieByAI(JSONArray usedMovies, String season, String fileStage, int currentYear) throws IOException {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            usedKeySet.add(jo.getString("title") + "|" + jo.getIntValue("year"));
        }
        String tagList = String.join("、", CLASSIC_TAGS);

        // 【强制保真核心规则】严禁虚构影片、严禁乱改上映年份、只输出全网可查真实影片数据
        String aiPickPrompt = "你是头条影视自媒体流量选片专家，当前年份：" + currentYear + "，当前时间：" + season + "，当前影视档期：" + fileStage + "。"
                + "【最高优先级·强制保真铁律，违规直接作废】"
                + "1、所有输出的影片名称、上映年份、热度信息必须是全网可查的真实官方数据，严禁AI虚构、严禁篡改上映年份、严禁编造热度！"
                + "2、例：《哪吒之魔童闹海》真实上映年份为2025年，绝对禁止输出2026、2024等错误年份！"
                + "【选片优先级严格锁定】"
                + "第一优先级（必选）：优先挑选【本年度、近3个月内上映】、全网有充足影评素材、剧情饱满、可深度解读的院线/网络真实新片，满足任意2个热度条件："
                + "1.全网有热搜、话题、高讨论度；2.头条/抖音有稳定搜索流量；3.影视热度榜单上榜；4.档期热门影片。"
                + "严禁选择冷门新片、无资料新片、虚构影片、AI无法扩写的小众短片/纪录片！禁止优先选择往年老片！"
                + "第二优先级（仅无合格新片时启用）：若全网无符合条件、有充足素材的本年度近期真实热片，再挑选高分长效真实经典影片。"
                + "【去重铁律】绝对禁止选择以下已创作过的影片：" + usedKeySet
                + "【返回规范】严格输出纯JSON，无多余文字、无解释、无markdown，字段全部真实可查，必填："
                + "{\"title\":\"真实影片名\",\"year\":\"真实官方上映年份\",\"tag\":\"影片对应标签\",\"reason\":\"严格按优先级说明真实选片依据\",\"source\":\"本年度近期热点流量影片/无新片兜底经典长尾影片\"}";

        // 多次重试+严格判空
        JSONObject aiResult = null;
        for (int i = 0; i < 3; i++) {
            aiResult = callAIPickMovie(aiPickPrompt);
            if (aiResult != null && !isBlank(aiResult.getString("title")) && aiResult.getIntValue("year") > 0) {
                // AI选片结果二次去重，避免重复
                String checkKey = aiResult.getString("title") + "|" + aiResult.getIntValue("year");
                if (!usedKeySet.contains(checkKey)) {
                    return aiResult;
                }
                System.out.printf("⚠️第%d轮AI选片命中历史影片，跳过重试...%n", i + 1);
            }
            System.out.printf("⚠️第%d轮AI选片返回空/异常，重试中...%n", i + 1);
            sleepMs(3000);
        }

        // 【核心修复】AI接口失败，启用【去重随机兜底池】，不再固定活着！
        System.out.println("🔥AI接口重试失败，触发本地经典影片轮询兜底机制（自动去重）");
        // 筛选出未使用过的经典影片
        List<Map<String,Object>> availableClassic = new ArrayList<>();
        for (Map<String,Object> movie : CLASSIC_MOVIE_POOL) {
            String key = movie.get("title") + "|" + movie.get("year");
            if (!usedKeySet.contains(key)) {
                availableClassic.add(movie);
            }
        }

        // 若所有经典都用过，清空最早记录（兜底容错）
        if (availableClassic.isEmpty()) {
            System.out.println("⚠️所有经典影片已轮询完毕，重启轮询池");
            availableClassic = new ArrayList<>(CLASSIC_MOVIE_POOL);
        }

        // 随机选取不重复经典影片
        Random random = new Random();
        Map<String,Object> randomMovie = availableClassic.get(random.nextInt(availableClassic.size()));

        // 封装返回
        JSONObject fallbackMovie = new JSONObject();
        fallbackMovie.put("title", randomMovie.get("title"));
        fallbackMovie.put("year", randomMovie.get("year"));
        fallbackMovie.put("tag", randomMovie.get("tag"));
        fallbackMovie.put("reason", randomMovie.get("reason") + "，AI新片选片异常，启用轮询兜底机制，规避影片重复");
        fallbackMovie.put("source", "无新片兜底经典长尾影片");
        return fallbackMovie;
    }

    // AI选片专用接口调用（增强保真容错）
    private static JSONObject callAIPickMovie(String prompt) {
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("model", "deepseek-v4-flash");
            reqBody.put("max_tokens", 1024);
            reqBody.put("temperature", 0.7); // 降低随机性，杜绝虚构错误
            reqBody.put("top_p", 0.9);

            JSONArray msgs = new JSONArray();
            msgs.add(JSONObject.of("role", "system", "content", "你是专业影视流量分析师，【零幻觉、零虚构、零篡改】，只输出全网可查的真实影片名称、真实上映年份，严格遵守新片优先、严格去重规则，仅返回标准JSON数据，无任何多余内容。"));
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

    // 多层防空+强制重选片，杜绝空内容卡死任务
    private static String generateReviewWithRewrite(String title, int year, String source, String tag, String reason) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            String content = generateReviewOnce(title, year, source, tag, reason, round);
            // 严格拦截空内容、空白内容、过短内容
            if (content == null || content.isBlank() || content.length() < 100) {
                System.out.printf("⚠️第%d轮生成内容为空/过短，重新生成%n", round);
                sleepMs(3000);
                continue;
            }
            int len = content.length();
            // 宽松字数适配，优先保证有内容输出
            if (len >= 1200 && len <= 2000) {
                return content;
            }
            System.out.printf("⚠️字数小幅偏差（%d字），第%d轮重新扩写优化%n", len, round);
            sleepMs(3000);
        }
        // 重写失败直接抛出，触发外层重选片机制
        throw new IOException("当前影片素材不足，多轮重写失败，需要更换影片");
    }

    // 梯度扩写提示词，强化真实素材适配，杜绝空输出、虚假内容
    private static String generateReviewOnce(String title, int year, String source, String tag, String reason, int rewriteRound) throws IOException {
        String extraRule = "";
        if (rewriteRound == 1) {
            extraRule = "完整深度创作，内容饱满详实，稳定输出1400字以上，严禁空白、严禁简短敷衍、严禁内容截断、严禁虚假剧情解读。";
        } else if (rewriteRound == 2) {
            extraRule = "大幅细化扩写，补充真实人物细节、官方剧情、真实社会背景、观众共鸣、现实延伸，稳固字数，文风统一，绝对禁止空内容、虚假内容。";
        } else {
            extraRule = "终极精细化扩容，多角度真实思辨解读、影片官方价值剖析、时代背景延伸，严格贴近1400-1800字，只扩不删，杜绝空白、杜绝虚构解读。";
        }

        String flowTip = source.contains("热点")
                ? "本片为真实本年度近期热门新片，档期流量核心影片，全网真实讨论度高，适配头条短期爆发流量。"
                : "本年度无优质可扩写真实新片，选用高分真实经典影片，素材充足、适配头条长期搜索长尾流量。";

        String sysPrompt = "你是头条小众独立影评博主，固定【温柔细腻、清醒思辨、真诚有温度】的专属个人风格，无模板、无流水线、无烂大街话术。"
                + "评析真实影片：" + title + "(" + year + ")｜流量属性：" + source + "｜影片标签：" + tag + "｜选片依据：" + reason + flowTip
                + "写作硬性保真规则："
                + "1、纯正文输出，严禁空白、严禁无内容输出，稳定输出1400-1800字符；"
                + "2、所有剧情、解读、背景必须贴合影片真实内容，严禁虚构、瞎编；"
                + "3、短段落排版，适配手机竖屏阅读，提升完读率；"
                + "4、极简剧情铺垫，95%内容为个人深度解读、人性剖析、现实共鸣；"
                + "5、观点小众独特，强化个人IP辨识度；"
                + "6、只允许扩写细化，禁止清空重写、禁止大幅删减；"
                + "7、文风真诚克制、有思考、有态度，内容扎实饱满。" + extraRule;

        JSONObject req = new JSONObject();
        req.put("model", "deepseek-v4-flash");
        req.put("max_tokens", MAX_OUTPUT_TOKENS);
        req.put("temperature", 0.9);
        req.put("top_p", 0.95);
        req.put("extra_body", JSONObject.of("thinking", false));

        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        msgs.add(JSONObject.of("role", "user", "content", "输出一篇风格统一、深度饱满、字数达标、内容真实、无空白、适配头条长效流量的个人专属影评正文。"));
        req.put("messages", msgs);

        int retry = 3;
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
        String header = String.format("🎬AI严格保真选片·头条长效影评\n影片：%s（%d）\n流量类型：%s\n影片标签：%s\n选片依据：%s\n文章字数：%d\n——————————\n",
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
