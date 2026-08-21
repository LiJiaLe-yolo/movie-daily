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
    // 大幅上调最大输出token，彻底解决字数上限不足问题
    private static final int MAX_OUTPUT_TOKENS = 3200;
    // 用户指定最终字数区间：1500-1800字符
    private static final int ARTICLE_MIN_LEN = 1500;
    private static final int ARTICLE_MAX_LEN = 1800;
    // AI重写最大次数
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
     * 优先级1：近期上映、有热度院线新片（核心优先池，优先写完所有新片再切换经典片）
     * 可手动持续更新当下热门院线电影，贴合实时影视热度
     */
    private static final JSONArray HOT_RECENT_POOL = JSONArray.parseArray("""
            [
              {"title":"好东西","year":2024},
              {"title":"浪浪山小妖怪","year":2025},
              {"title":"小丑2","year":2024}
            ]
            """);
    /**
     * 优先级2：高品位品质储备库
     * 仅当所有近期热门新片全部写完后，自动切换至此经典片库
     * 全部为优质文艺、现实、高分经典影片，适配深度影评创作
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
            // 严格新片优先选片逻辑
            JSONObject selectMovie = pickMovie(usedMovies);
            String title = selectMovie.getString("title");
            int year = selectMovie.getIntValue("year");
            String source = selectMovie.getString("source");
            System.out.printf("今日选中影片：%s (%d)｜选片来源：%s%n", title, year, source);
            // AI生成影评，内置字数不合格自动重写
            String articleContent = generateReviewWithRewrite(title, year, source);
            int articleLen = articleContent.length();
            System.out.printf("生成完成，文章字符长度：%d%n", articleLen);
            // 兜底字数校验
            if (articleLen < ARTICLE_MIN_LEN || articleLen > ARTICLE_MAX_LEN) {
                throw new RuntimeException("影评字数校验不通过！实际=" + articleLen
                        + "，要求：" + ARTICLE_MIN_LEN + "-" + ARTICLE_MAX_LEN);
            }
            // 本地落盘输出文件
            saveOutput(title, year, source, articleContent);
            // 写入Gist历史库，防止重复选题
            appendToGistHistory(gistData, title, year, source);
            // 飞书推送【修复版完整卡片全文推送】
            try {
                sendFeishuFullCardArticle(title, year, source, articleContent, articleLen);
                System.out.println("✅飞书全文卡片推送完成，无截断、无静默拦截");
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
     * 选片业务逻辑（强化新片优先）
     * 1、优先遍历近期热点院线新片池，优先选用未创作过的影片，直至全部耗尽
     * 2、仅新片池无剩余影片时，才切换高品位经典片库
     * 3、Gist全局去重，永久不重复创作同一影片影评
     */
    private static JSONObject pickMovie(JSONArray usedMovies) {
        Set<String> usedKeySet = new HashSet<>();
        for (Object o : usedMovies) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            usedKeySet.add(key);
        }
        // 第一步：优先遍历【近期热点院线新片池】，穷尽所有未使用新片
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
            pick.put("source", "近期热点院线新片");
            return pick;
        }
        // 第二步：新片池完全耗尽，才启用经典品质片库
        List<JSONObject> artCandidates = new ArrayList<>();
        for (Object o : QUALITY_ART_POOL) {
            JSONObject jo = (JSONObject) o;
            String key = jo.getString("title") + "|" + jo.getIntValue("year");
            if (!usedKeySet.contains(key)) {
                artCandidates.add(jo);
            }
        }
        if (artCandidates.isEmpty()) {
            throw new RuntimeException("热点新片池、经典品质片库全部耗尽，请手动补充片库！");
        }
        Random r = new Random();
        JSONObject pick = artCandidates.get(r.nextInt(artCandidates.size()));
        pick.put("source", "经典高分品质影片");
        return pick;
    }
    /** 带字数不合格自动多轮重写，新增重写差异化提示，避免重复短文本 */
    private static String generateReviewWithRewrite(String title, int year, String source) throws IOException {
        for (int round = 1; round <= MAX_REWRITE_TIMES; round++) {
            System.out.printf("------ AI生成第 %d 轮 ------%n", round);
            // 每一轮重写传入不同指令，强制扩写内容，杜绝字数偏低
            String content = generateReviewOnce(title, year, source, round);
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
    /**
     * 优化升级版生成方法：强化个人文风、动态自适应分段、彻底修复格式报错
     */
    private static String generateReviewOnce(String title, int year, String source, int rewriteRound) throws IOException {
        // 差异化Prompt：首轮正常生成，后续轮次强制细节扩写、深化感受
        String extraRule = "";
        if (rewriteRound == 2) {
            extraRule = "当前为第二轮重写，必须大幅扩写个人感悟、细化生活共鸣细节，丰富心理描写与镜头解读，杜绝内容单薄，严格补足字数。";
        } else if (rewriteRound == 3) {
            extraRule = "当前为第三轮强制扩写，极致填充私人化体验、细碎情绪、生活化细节，拉长段落感悟表述，保证文章饱满厚重，稳定达标1500字以上。";
        }
        // 全新私人文风+动态分段核心规则，彻底告别千篇一律模板
        String sysPrompt = "你是一位极具个人特色的小众独立影评博主，文风温柔细腻、自带烟火气、私人化、不大众化、不流水线、不堆砌辞藻。"
                + "写作核心：拒绝全网通用影评套路，不用固定模板、不用套话空话、不用教科书式点评，以「普通人观影后的真实情绪独白」为核心，写独属于个人的观影感悟。"
                + "今日影片：" + title + "（" + year + "）；选片来源：" + source + "。"
                + "硬性写作规则（必须100%遵守）："
                + "1、全文严格控制在【1500‑1800个中文字符】，必须达标下限，严禁字数不足，纯段落排版，不要标题、不要摘要、不要打分、不要小结；"
                + "2、【动态自适应分段】摒弃固定段落数量，根据全文内容自然拆分，内容饱满则多分小段、内容舒缓则适度合并，整体段落错落有致，排版自然舒适，杜绝扎堆大段；"
                + "3、剧情介绍仅保留1-2句极简铺垫，绝不复述剧情、不剧透细节、不占用正文篇幅，核心全部为私人情绪、生活共鸣、细节解读、自我感悟；"
                + "4、文风差异化要求：摒弃千篇一律的影评话术，多用个人视角的细碎感受、生活碎片、职场共情、日常情绪代入，文字松弛、真诚、有温度，自带个人专属风格，全网不撞文；"
                + "5、允许写出多元观点：可以有惋惜、不解、轻微吐槽、自我和解、反向思考等真实主观情绪，拒绝单一吹捧、拒绝完美化评价，真实不刻意；"
                + "6、深度挖掘镜头留白、人物隐性情绪、画面细节，结合当代年轻人内耗、通勤、独居、职场压力、乡愁等真实生活状态强化共鸣；"
                + "7、杜绝所有网络烂词、营销式话术、模板化句式，不刻意拔高主题、不强行升华，保持自然独白感，像私人随笔一样真诚细腻；"
                + "8、每段内容充分展开，细化心理活动、观影瞬间、生活联想，保证内容饱满厚重，稳稳达标字数区间，不敷衍、不精简。"
                + extraRule;
        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "deepseek-v4-flash");
        reqBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        // 调高随机性，强化文风独特性，避免重复模板内容
        reqBody.put("temperature", 0.88);
        reqBody.put("top_p", 0.95);
        reqBody.put("extra_body", JSONObject.of("thinking", false));
        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "system", "content", sysPrompt));
        msgs.add(JSONObject.of("role","user","content","请严格遵守私人化文风、动态分段、字数约束规则，输出真诚独特、无模板、细节饱满的完整影评正文。"));
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
        // 统一换行格式，保证分段整洁
        s = s.replace("\r\n", "\n");
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
    // ============ 【最终修复版】飞书完整全文卡片推送 ============
    // 解决：纯文本超长被飞书静默拦截、无报错不推送问题
    // 逻辑：自动分段拆分全文、卡片承载、100%完整展示、不截断、不丢失
    private static void sendFeishuFullCardArticle(String title, int year, String source, String content, int contentLen) throws IOException {
        // 头部基础信息
        String header = String.format("**🎬今日影评：%s（%d）**\n📌选片来源：%s\n📝文章字数：%d字符\n——————————————\n\n",
                title, year, source, contentLen);
        // 飞书单卡片安全阈值：1200字符，自动拆分全文，保证每段都能推送成功
        int chunkSize = 1200;
        // 先推头部信息卡片
        sendFeishuCardChunk(header);
        // 正文循环分片推送，保证整篇完整送达
        int len = content.length();
        for (int i = 0; i < len; i += chunkSize) {
            int end = Math.min(i + chunkSize, len);
            String chunk = content.substring(i, end);
            sendFeishuCardChunk(chunk);
            // 短时休眠，防止飞书接口限流
            sleepMs(300);
        }
    }
    // 通用单段卡片推送方法
    private static void sendFeishuCardChunk(String text) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("msg_type", "interactive");
        JSONObject cardBody = new JSONObject();
        cardBody.put("wide_screen_mode", true);
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", text)));
        cardBody.put("elements", elements);
        payload.put("card", cardBody);
        RequestBody rb = RequestBody.create(payload.toString(), MediaType.parse("application/json;charset=utf-8"));
        Request req = new Request.Builder()
                .url(FEISHU_WEBHOOK_MOVIE)
                .post(rb)
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("推送分片失败，状态码：" + resp.code());
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
