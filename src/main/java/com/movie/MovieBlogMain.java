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

    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String GH_PAT = System.getenv("GH_PAT");
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String FEISHU_WEBHOOK_MOVIE = System.getenv("FEISHU_WEBHOOK_MOVIE");
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String AI_MODEL = "deepseek-v4-flash";

    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int ARTICLE_MIN_LEN = 1400;
    private static final int ARTICLE_MAX_LEN = 1800;
    private static final int MAX_REWRITE_TIMES = 3;
    private static final int MAX_RESELECT_TIMES = 2;
    private static final int MAX_HISTORY_SIZE = 500;
    private static final String GIST_FILENAME = "movie_history.json";
    private static final String OUTPUT_DIR = "output";

    // 仅作为最终安全网，配套生成模式下几乎不会触发
    private static final List<String> FALLBACK_TITLE_TEMPLATES = Arrays.asList(
            "《{m}》：那些被忽略的细节，藏着最真实的人性",
            "重温《{m}》，才读懂了导演没明说的隐喻",
            "为什么《{m}》后劲这么大？这几个细节太戳人了",
            "《{m}》深度解析：看懂这些，才算没白看",
            "初看不知片中意，再看已是《{m}》局中人",
            "《{m}》里最扎心的一幕，成年人看了都沉默",
            "别只当爆米花电影看，《{m}》的细节细思极恐",
            "《{m}》观影指南：这3个隐藏彩蛋你发现了吗？",
            "看完《{m}》才发现，我们都在演别人的故事",
            "《{m}》最狠的不是剧情，而是照进现实的镜子",
            "十年后再看《{m}》，终于理解了那句台词的重量",
            "《{m}》：一部被片名耽误的神作，值得N刷",
            "全网都在聊《{m}》，但90%的人没看懂这个细节",
            "《{m}》里藏着的暗线，比主线更让人破防",
            "以为是爽片，结果《{m}》把我看哭了三次",
            "《{m}》：成年人的崩溃，都藏在这些镜头里",
            "刷完《{m}》才明白，有些遗憾注定无法弥补",
            "《{m}》最被低估的一场戏，信息量太大了",
            "不敢二刷《{m}》，不是不好看，是太疼了",
            "《{m}》结局反转背后，藏着导演最深的温柔"
    );

    private static final List<Map<String, Object>> CLASSIC_MOVIE_POOL;
    static {
        CLASSIC_MOVIE_POOL = new ArrayList<>();
        CLASSIC_MOVIE_POOL.add(Map.of("title", "活着", "year", 1994, "tag", "人性传世经典、现实高分经典", "reason", "国产顶级现实经典，素材充足，适配长效流量"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "霸王别姬", "year", 1993, "tag", "影史封神经典、时代叙事经典", "reason", "华语影史天花板，解读角度极多，流量稳定"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "阿甘正传", "year", 1994, "tag", "励志传世经典、人生治愈经典", "reason", "全球高分常青佳作，受众极广，长尾流量充足"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "肖申克的救赎", "year", 1994, "tag", "人性博弈经典、逆袭励志经典", "reason", "影史高分榜首，常年热搜，可深度解读维度丰富"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "山海情", "year", 2021, "tag", "现实纪实经典、家国温情佳作", "reason", "国产高分现实题材，口碑过硬，适配大众共鸣流量"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "你好，李焕英", "year", 2021, "tag", "家庭治愈经典、温情现实佳作", "reason", "国民级温情影片，受众广泛，讨论度持久"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "千与千寻", "year", 2001, "tag", "治愈文艺经典、成长寓言佳作", "reason", "日系传世动画，常年有搜索流量，解读维度丰富"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "寻梦环游记", "year", 2017, "tag", "亲情治愈经典、奇幻温情佳作", "reason", "亲情治愈顶流动画，大众好感度高，适配自媒体流量"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "让子弹飞", "year", 2010, "tag", "黑色幽默经典、现实隐喻佳作", "reason", "国产神作，常看常新，解读空间极大"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "楚门的世界", "year", 1998, "tag", "哲学思辨经典、人性觉醒佳作", "reason", "极具前瞻性，契合当下社会情绪"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "星际穿越", "year", 2014, "tag", "科幻温情经典、宇宙浪漫佳作", "reason", "硬核科幻与极致亲情的结合"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "我不是药神", "year", 2018, "tag", "现实催泪经典、社会良知佳作", "reason", "国产现实题材里程碑，社会痛点精准"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "泰坦尼克号", "year", 1997, "tag", "爱情史诗经典、灾难视听佳作", "reason", "全球影史票房奇迹，流量永不过时"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "盗梦空间", "year", 2010, "tag", "悬疑烧脑经典、科幻叙事神作", "reason", "诺兰代表作，逻辑严密，极易产出深度解析"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "辛德勒的名单", "year", 1993, "tag", "人性光辉经典、战争反思佳作", "reason", "影史不朽丰碑，沉重而深刻"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "美丽人生", "year", 1997, "tag", "温情治愈经典、父爱如山佳作", "reason", "笑中带泪，极度契合治愈系调性"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "大话西游之大圣娶亲", "year", 1995, "tag", "解构主义经典、后现代爱情佳作", "reason", "华语cult神作，金句频出"));
        CLASSIC_MOVIE_POOL.add(Map.of("title", "当幸福来敲门", "year", 2006, "tag", "逆袭励志经典、父子温情佳作", "reason", "全球公认励志教科书，情绪价值拉满"));
    }

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ==================== 主流程 ====================
    public static void main(String[] args) {
        try {
            checkEnv();
            initDir();
            System.out.println("===== 单卡推送+兜底轮询+杜绝幻觉任务启动 =====");

            String currentSeason = getCurrentSeason();
            String currentFileStage = getCurrentMovieFileStage();
            int currentYear = LocalDate.now().getYear();
            System.out.printf("✅系统自动识别：当前年份%d｜季节：%s｜档期：%s%n", currentYear, currentSeason, currentFileStage);

            JSONObject gistData = safeReadGist();
            JSONArray usedMovies = gistData.getJSONArray("used_movies");
            System.out.printf("读取全局去重片库：%d条%n", usedMovies.size());

            JSONObject selectMovie = null;
            String articleContent = null;
            List<String> titles = null;

            for (int reSelect = 0; reSelect <= MAX_RESELECT_TIMES; reSelect++) {
                selectMovie = autoPickMovieByAI(usedMovies, currentSeason, currentFileStage, currentYear);
                String title = selectMovie.getString("title");
                int year = selectMovie.getIntValue("year");
                String source = selectMovie.getString("source");
                String movieTag = selectMovie.getString("tag");
                String selectReason = selectMovie.getString("reason");

                System.out.printf("✅第%d轮选片：%s(%d)｜流量类型：%s｜影片标签：%s｜选片依据：%s%n",
                        reSelect + 1, title, year, source, movieTag, selectReason);

                try {
                    // 【核心改动】影评与标题配套生成，返回结果为 "标题区\n\n正文区"
                    String combinedResult = generateArticleWithTitles(title, year, source, movieTag, selectReason);
                    
                    // 解析分离标题和正文
                    String[] parsed = parseCombinedResult(combinedResult, title);
                    titles = parseTitlesFromBlock(parsed[0], title);
                    articleContent = parsed[1];
                    
                    if (articleContent != null && !articleContent.isBlank()) {
                        break;
                    }
                } catch (Exception e) {
                    System.out.printf("⚠️当前影片生成失败，触发第%d次重选片 | %s%n", reSelect + 1, e.getMessage());
                    sleepMs(2000);
                }
            }

            if (articleContent == null || articleContent.isBlank()) {
                throw new RuntimeException("多次选片+重写均生成失败，任务终止");
            }

            int articleLen = articleContent.length();
            System.out.printf("📝影评生成完成，字数：%d%n", articleLen);
            if (articleLen < ARTICLE_MIN_LEN || articleLen > ARTICLE_MAX_LEN) {
                System.out.printf("⚠️字数偏差（%d字），兜底放行%n", articleLen);
            }

            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            String movieTag = selectMovie.getString("tag");
            String selectReason = selectMovie.getString("reason");

            // 确保标题列表有效
            if (titles == null || titles.isEmpty()) {
                titles = generateFallbackTitles(title);
            }
            
            System.out.println("🔥候选标题：");
            for (int i = 0; i < titles.size(); i++) {
                System.out.println("   候选标题 " + (i + 1) + ": " + titles.get(i));
            }

            saveOutput(title, year, source, movieTag, selectReason, articleContent, titles);
            appendToGistHistory(gistData, title, year, source, movieTag, selectReason);

            try {
                sendFeishuSingleCardArticle(title, year, source, movieTag, selectReason, articleContent, articleLen, titles);
                System.out.println("✅全文单卡推送成功！任务正常完成");
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

    // ==================== 选片逻辑 ====================
    private static JSONObject autoPickMovieByAI(JSONArray usedMovies, String season, String fileStage, int currentYear) throws IOException {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            usedKeySet.add(buildMovieKey(jo.getString("title"), jo.get("year")));
        }

        String aiPickPrompt = "你是影视自媒体选片专家。当前年份：" + currentYear + "，" + season + "，" + fileStage + "。\n"
                + "请推荐一部适合写深度影评的真实电影，直接输出JSON，禁止思考过程和多余文字。\n"
                + "【选片规则】\n"
                + "1. 严禁虚构！不确定是否存在的电影绝对不要选；\n"
                + "2. 优先选" + currentYear + "年近3个月上映的真实新片；如果你不确定有什么新片，直接选一部豆瓣8分以上的经典影片，不要纠结；\n"
                + "3. 绝对禁止选择以下已创作过的影片：" + usedKeySet + "\n"
                + "【输出格式】纯JSON，无markdown，无解释：\n"
                + "{\"title\":\"电影名\",\"year\":年份数字,\"tag\":\"两个标签用顿号分隔\",\"reason\":\"20字内选片理由\",\"source\":\"流量类型\"}";

        JSONObject aiResult = null;
        for (int i = 0; i < 5; i++) {
            System.out.printf("🔄 第 %d/5 轮AI选片...%n", i + 1);
            aiResult = callAIPickMovie(aiPickPrompt);

            if (aiResult == null) {
                System.err.println("⚠️ 第" + (i + 1) + "轮返回null，3秒后重试");
                sleepMs(3000);
                continue;
            }

            String title = aiResult.getString("title");
            Object yearObj = aiResult.get("year");

            if (isBlank(title)) {
                System.err.println("⚠️ 第" + (i + 1) + "轮title为空 | " + aiResult.toJSONString());
                sleepMs(3000);
                continue;
            }

            int year = parseYear(yearObj);
            if (year <= 0) {
                System.err.println("⚠️ 第" + (i + 1) + "轮year无效: " + yearObj);
                sleepMs(3000);
                continue;
            }

            String checkKey = buildMovieKey(title, year);
            if (!usedKeySet.contains(checkKey)) {
                System.out.printf("✅ 第%d轮选片成功: %s (%d)%n", i + 1, title, year);
                aiResult.put("title", title.trim().replaceAll("^[《]|[》]$", ""));
                aiResult.put("year", year);
                return aiResult;
            }
            System.out.printf("⚠️ 第%d轮命中历史影片（%s），重试...%n", i + 1, checkKey);
            sleepMs(3000);
        }

        System.out.println("🔥AI重试5次失败，触发本地经典兜底");
        List<Map<String, Object>> availableClassic = new ArrayList<>();
        for (Map<String, Object> movie : CLASSIC_MOVIE_POOL) {
            String key = buildMovieKey(movie.get("title").toString(), movie.get("year"));
            if (!usedKeySet.contains(key)) {
                availableClassic.add(movie);
            }
        }

        if (availableClassic.isEmpty()) {
            throw new IOException("AI选片失败且本地备用片库18部已全部耗尽，任务终止以防重复");
        }

        Map<String, Object> randomMovie = availableClassic.get(new Random().nextInt(availableClassic.size()));
        JSONObject fallback = new JSONObject();
        fallback.put("title", randomMovie.get("title").toString().trim().replaceAll("^[《]|[》]$", ""));
        fallback.put("year", randomMovie.get("year"));
        fallback.put("tag", randomMovie.get("tag"));
        fallback.put("reason", randomMovie.get("reason") + "，AI选片异常，启用兜底");
        fallback.put("source", "无新片兜底经典长尾影片");
        return fallback;
    }

    private static JSONObject callAIPickMovie(String prompt) {
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("model", AI_MODEL);
            reqBody.put("max_tokens", 4096);
            reqBody.put("temperature", 0.7);
            reqBody.put("top_p", 0.9);

            JSONArray msgs = new JSONArray();
            msgs.add(JSONObject.of("role", "system", "content", "直接输出一个合法JSON，禁止思考过程和解释。"));
            msgs.add(JSONObject.of("role", "user", "content", prompt));
            reqBody.put("messages", msgs);

            RequestBody body = RequestBody.create(reqBody.toString(), MediaType.parse("application/json;charset=utf-8"));
            Request req = new Request.Builder().url(DEEPSEEK_URL)
                    .header("Authorization", "Bearer " + DEEPSEEK_API_KEY).post(body).build();

            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() != null ? resp.body().string() : "无响应体";
                    System.err.println("❌ API失败 | 状态码:" + resp.code() + " | " + err);
                    return null;
                }
                String resStr = resp.body().string();
                if (isBlank(resStr)) return null;

                JSONObject resJson = JSONObject.parseObject(resStr);
                JSONArray choices = resJson.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) return null;

                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                String content = extractContent(message);

                if (isBlank(content)) {
                    System.err.println("❌ content为空 | finish_reason: "
                            + choices.getJSONObject(0).getString("finish_reason"));
                    return null;
                }

                System.out.println("🤖 AI返回: " + content);

                int s = content.indexOf('{');
                int e = content.lastIndexOf('}');
                if (s != -1 && e > s) content = content.substring(s, e + 1);
                if (isBlank(content)) return null;

                return JSONObject.parseObject(content);
            }
        } catch (Exception e) {
            System.err.println("❌ AI选片异常: " + e.getMessage());
            return null;
        }
    }

    // ==================== 影评+标题配套生成（核心改动） ====================
    private static String generateArticleWithTitles(String title, int year, String source, String tag, String reason) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            String combined = generateArticleOnce(title, year, source, tag, reason, round);
            if (combined == null || combined.isBlank() || combined.length() < 200) {
                sleepMs(3000);
                continue;
            }
            // 简单校验：必须包含分隔标记或足够长度
            if (combined.contains("===ARTICLE_START===") || combined.length() >= 1200) {
                return combined;
            }
            sleepMs(3000);
        }
        throw new IOException("当前影片素材不足，多轮配套生成失败");
    }

    private static String generateArticleOnce(String title, int year, String source, String tag, String reason, int rewriteRound) throws IOException {
        String extraRule;
        if (rewriteRound == 1) {
            extraRule = "完整深度创作，内容饱满，1400字以上。";
        } else if (rewriteRound == 2) {
            extraRule = "细化扩写，补充细节与共鸣，稳固字数。";
        } else {
            extraRule = "精细化扩容，多角度思辨，严格贴合1400-1800字。";
        }

        String flowTip = source.contains("热点")
                ? "本年度热门新片，短期流量充足。"
                : "经典高分影片，长尾流量稳定。";

        // 【核心改动】Prompt要求同时输出3个标题和正文，用固定标记分隔
        String sysPrompt = "你是一位拥有千万粉丝的深度影评博主，风格温柔细腻、清醒思辨、真诚有温度。你写的不是影评，而是借电影讲人性和生活。\n"
                + "评析影片：" + title + "(" + year + ")｜" + tag + "｜" + reason + "｜" + flowTip + "\n\n"
                + "【任务】请先为这篇影评构思3个爆款标题，然后再写正文。标题必须是基于你即将写的正文内容提炼出来的，与正文强关联。\n\n"
                + "【标题要求】\n"
                + "1. 每个标题15-35字，必须包含电影名或角色名；\n"
                + "2. 3个标题风格完全不同：一个悬念反差型、一个情感共鸣型、一个细节切入型；\n"
                + "3. 必须有具体细节（台词/场景/数字），禁止空泛概括；\n"
                + "4. 严禁使用\"深度解读\"\"被低估的佳作\"\"看懂了才算\"等烂大街句式；\n"
                + "5. 只输出3行纯文本标题，不要序号、不要前缀、不要markdown。\n\n"
                + "【正文写作铁律】\n"
                + "1. 严禁复述剧情！只写感受和洞察；\n"
                + "2. 开头必须是一个让人停下来的句子：反常识观点、扎心提问、或电影中最容易被忽略的细节；\n"
                + "3. 全文围绕1-2个核心洞察展开，像跟朋友深夜聊天，不要面面俱到；\n"
                + "4. 必须把电影和真实生活连接：职场困境、亲情遗憾、成长代价、中年危机、普通人的挣扎，让读者觉得\"说的就是我\"；\n"
                + "5. 至少引用2处电影中的具体台词或场景作为论据，让文章有血有肉；\n"
                + "6. 结尾不要喊口号，用一个安静的、余韵悠长的句子收束，让人读完想沉默一会儿；\n"
                + "7. 短段落，每段不超过3行，适配手机阅读。禁止\"首先\"\"其次\"\"综上所述\"\"总而言之\"等AI腔调；\n"
                + "8. 输出1400-1800字饱满正文。" + extraRule + "\n\n"
                + "【输出格式】严格遵守以下格式，不要有任何额外说明：\n"
                + "标题1\n标题2\n标题3\n===ARTICLE_START===\n正文内容";

        JSONObject req = new JSONObject();
        req.put("model", AI_MODEL);
        req.put("max_tokens", MAX_OUTPUT_TOKENS);
        req.put("temperature", 0.92);
        req.put("top_p", 0.95);

        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        msgs.add(JSONObject.of("role", "user", "content", "请为《" + title + "》生成3个配套标题和一篇深度影评正文。"));
        req.put("messages", msgs);

        for (int i = 0; i < 3; i++) {
            try {
                RequestBody body = RequestBody.create(req.toString(), MediaType.parse("application/json;charset=utf-8"));
                Response resp = HTTP_CLIENT.newCall(new Request.Builder().url(DEEPSEEK_URL)
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY).post(body).build()).execute();

                JSONObject resJson = JSONObject.parseObject(resp.body().string());
                JSONObject message = resJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                String raw = extractContent(message);
                return cleanAiContent(raw);
            } catch (Exception e) {
                System.err.println("⚠️ 配套生成第" + (i + 1) + "次异常: " + e.getMessage());
                sleepMs(2000);
            }
        }
        throw new IOException("配套生成接口请求失败");
    }

    /**
     * 解析配套生成的结果，分离标题区和正文区
     */
    private static String[] parseCombinedResult(String combined, String movieTitle) {
        if (combined == null || combined.isBlank()) {
            return new String[]{"", ""};
        }
        
        String separator = "===ARTICLE_START===";
        int sepIdx = combined.indexOf(separator);
        
        if (sepIdx != -1) {
            String titleBlock = combined.substring(0, sepIdx).trim();
            String articleBlock = combined.substring(sepIdx + separator.length()).trim();
            return new String[]{titleBlock, articleBlock};
        }
        
        // 降级处理：如果没有分隔标记，尝试按前3行作为标题
        String[] lines = combined.split("\n");
        StringBuilder titleSb = new StringBuilder();
        int articleStartLine = 0;
        for (int i = 0; i < Math.min(lines.length, 5); i++) {
            String line = lines[i].trim();
            // 如果某行明显是正文特征（很长、含段落标记），则认为前面的是标题
            if (line.length() > 50 || line.startsWith("##") || line.startsWith("**")) {
                articleStartLine = i;
                break;
            }
            if (!line.isEmpty() && (line.contains("《") || line.contains(movieTitle))) {
                titleSb.append(line).append("\n");
                articleStartLine = i + 1;
            }
        }
        
        StringBuilder articleSb = new StringBuilder();
        for (int i = articleStartLine; i < lines.length; i++) {
            articleSb.append(lines[i]).append("\n");
        }
        
        return new String[]{titleSb.toString().trim(), articleSb.toString().trim()};
    }

    /**
     * 从标题块中解析出有效的标题列表
     */
    private static List<String> parseTitlesFromBlock(String titleBlock, String movieTitle) {
        List<String> titles = new ArrayList<>();
        if (titleBlock == null || titleBlock.isBlank()) {
            return titles;
        }
        
        for (String line : titleBlock.split("\n")) {
            String clean = line.trim()
                    .replaceAll("^[0-9]+[.、)\\]:：]+\\s*", "")
                    .replaceAll("^标题[0-9]+[：:]\\s*", "")
                    .replaceAll("^[*\\-]\\s*", "")
                    .replaceAll("^\"|\"$", "")
                    .trim();
            
            if (clean.length() >= 10 && clean.length() <= 50
                && (clean.contains("《") || clean.contains(movieTitle))
                && !clean.contains("思考")
                && !clean.contains("分析")
                && !clean.startsWith("好的")
                && !clean.startsWith("以下是")
                && !clean.startsWith("当然")
                && !clean.contains("===")) {
                titles.add(clean);
            }
            if (titles.size() >= 3) break;
        }
        return titles;
    }

    /**
     * 生成兜底标题（仅在配套生成完全失败时调用）
     */
    private static List<String> generateFallbackTitles(String movieTitle) {
        List<String> titles = new ArrayList<>();
        Random random = new Random();
        while (titles.size() < 3) {
            String template = FALLBACK_TITLE_TEMPLATES.get(random.nextInt(FALLBACK_TITLE_TEMPLATES.size()));
            String fallbackTitle = template.replace("{m}", movieTitle);
            if (!titles.contains(fallbackTitle)) {
                titles.add(fallbackTitle);
            }
        }
        return titles;
    }

    // ==================== 工具方法 ====================
    private static String extractContent(JSONObject message) {
        String content = message.getString("content");
        if (isBlank(content)) {
            String reasoning = message.getString("reasoning_content");
            if (!isBlank(reasoning)) {
                System.out.println("⚠️ content为空，尝试从reasoning_content提取");
                content = reasoning;
            }
        }
        if (content != null) {
            content = content.replaceAll("(?is)<think>.*?</think>", "").trim();
            content = content.replaceAll("(?is)思考过程：.*?(?=\\n|$)", "").trim();
        }
        return content;
    }

    private static String buildMovieKey(String title, Object yearObj) {
        if (title == null) return "unknown|0";
        String cleanTitle = title.trim().replaceAll("^[《]|[》]$", "").replaceAll("\\s+", "");
        return cleanTitle + "|" + parseYear(yearObj);
    }

    private static int parseYear(Object yearObj) {
        if (yearObj instanceof Number) return ((Number) yearObj).intValue();
        if (yearObj != null) {
            String s = yearObj.toString().replaceAll("[^0-9]", "");
            if (!s.isEmpty()) {
                try { return Integer.parseInt(s); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static String cleanAiContent(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^```markdown|^```|``` $ ", "").replace("\r\n", "\n").trim();
    }

    private static String getCurrentSeason() {
        int m = LocalDate.now().getMonthValue();
        if (m >= 3 && m <= 5) return "春季";
        if (m >= 6 && m <= 8) return "夏季";
        if (m >= 9 && m <= 11) return "秋季";
        return "冬季";
    }

    private static String getCurrentMovieFileStage() {
        int m = LocalDate.now().getMonthValue();
        if (m == 1 || m == 2) return "春节贺岁档";
        if (m >= 3 && m <= 5) return "春季常规档期";
        if (m >= 6 && m <= 8) return "暑期黄金档期";
        if (m == 9 || m == 10) return "国庆黄金档期";
        return "年末贺岁预热档期";
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

    // ==================== 文件与Gist操作 ====================
    private static void saveOutput(String title, int year, String source, String tag,
                                   String reason, String content, List<String> titles) throws IOException {
        Files.write(Paths.get(OUTPUT_DIR, "movie_article.md"), content.getBytes(StandardCharsets.UTF_8));
        JSONObject meta = new JSONObject();
        meta.put("title", title);
        meta.put("year", year);
        meta.put("flow_source", source);
        meta.put("movie_tag", tag);
        meta.put("select_reason", reason);
        meta.put("len", content.length());
        meta.put("gen_time", System.currentTimeMillis());
        meta.put("titles", titles);
        Files.write(Paths.get(OUTPUT_DIR, "movie_meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject safeReadGist() throws IOException {
        for (int r = 0; r < 2; r++) {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID + "?t=" + System.currentTimeMillis())
                        .header("Authorization", "token " + GH_PAT)
                        .header("Cache-Control", "no-cache")
                        .get().build();
                Response resp = HTTP_CLIENT.newCall(req).execute();
                JSONObject gist = JSONObject.parseObject(resp.body().string());
                return JSONObject.parseObject(
                        gist.getJSONObject("files").getJSONObject(GIST_FILENAME).getString("content"));
            } catch (Exception e) {
                System.err.println("⚠️ Gist读取第" + (r + 1) + "次异常: " + e.getMessage());
                sleepMs(1000);
            }
        }
        throw new IOException("读取GIST历史库失败");
    }

    private static void appendToGistHistory(JSONObject gistData, String title, int year,
                                            String source, String tag, String reason) throws IOException {
        JSONArray used = gistData.getJSONArray("used_movies");
        JSONObject item = new JSONObject();
        item.put("title", title);
        item.put("year", year);
        item.put("source", source);
        item.put("tag", tag);
        item.put("reason", reason);
        item.put("gen_time", System.currentTimeMillis());
        used.add(item);
        while (used.size() > MAX_HISTORY_SIZE) used.remove(0);

        JSONObject body = new JSONObject();
        JSONObject file = new JSONObject();
        file.put("content", JSON.toJSONString(gistData));
        JSONObject files = new JSONObject();
        files.put(GIST_FILENAME, file);
        body.put("files", files);

        for (int r = 0; r < 2; r++) {
            try {
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
                        .method("PATCH", rb).build();
                if (HTTP_CLIENT.newCall(req).execute().isSuccessful()) return;
            } catch (Exception e) {
                System.err.println("⚠️ Gist写入第" + (r + 1) + "次异常: " + e.getMessage());
                sleepMs(1000);
            }
        }
        throw new IOException("写入GIST历史库失败");
    }

    // ==================== 飞书推送 ====================
    private static void sendFeishuSingleCardArticle(String title, int year, String source, String tag,
                                                    String reason, String content, int len,
                                                    List<String> titles) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🎬**AI严格保真选片·头条长效影评**\n**影片**：%s（%d）\n**流量类型**：%s\n**影片标签**：%s\n**选片依据**：%s\n**文章字数**：%d\n\n",
                title, year, source, tag, reason, len));
        sb.append("**🔥 爆款标题推荐（任选其一）：**\n");
        for (int i = 0; i < titles.size(); i++) {
            sb.append(String.format("%d. %s\n", i + 1, titles.get(i)));
        }
        sb.append("——————————\n\n");
        sb.append(content);

        JSONObject payload = new JSONObject();
        payload.put("msg_type", "interactive");
        JSONObject card = new JSONObject();
        card.put("wide_screen_mode", true);
        card.put("header", JSONObject.of(
                "title", JSONObject.of("tag", "plain_text", "content", "🎬 每日影评推送：" + title),
                "template", "blue"));
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div",
                "text", JSONObject.of("tag", "lark_md", "content", sb.toString())));
        card.put("elements", elements);
        payload.put("card", card);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json;charset=utf-8"));
        HTTP_CLIENT.newCall(new Request.Builder().url(FEISHU_WEBHOOK_MOVIE).post(body).build()).execute();
    }

    // ==================== 基础工具 ====================
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleepMs(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); } catch (Exception ignored) {}
    }
}
