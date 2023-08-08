package com.xkcoding.mpwechatdemo.controller;

import cn.hutool.core.date.DateTime;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xkcoding.magic.core.tool.api.R;
import com.xkcoding.magic.core.tool.enums.CommonResultCode;
import com.xkcoding.magic.core.tool.exception.ServiceException;
import com.xkcoding.mpwechatdemo.autoconfiguration.MpWeChatProperties;
import com.xkcoding.mpwechatdemo.entity.WechatLoginParam;
import com.xkcoding.mpwechatdemo.enums.EventType;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.xml.sax.InputSource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.util.HashMap;

@Slf4j
@RestController
public class MpWechatController {
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";

    private static final String TICKET_URL = "https://api.weixin.qq.com/cgi-bin/qrcode/create?access_token=%s";

    private static final String NEW_TICKET_URL = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=%s&type=jsapi";

    private static final String QR_CODE_URL = "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=%s";

    private static final String USER_INFO_URL = "https://api.weixin.qq.com/cgi-bin/user/info?access_token=%s&openid=%s&lang=zh_CN";

    @Autowired
    private final MpWeChatProperties properties;

    // 有效期
    long enableTimestamp = 0;
    // 过渡时间 提早5分钟，重新获取token
    long transitionTime = 5 * 60 * 1000;
    private String ticket = "";

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public MpWechatController(MpWeChatProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成登录二维码
     */
    @GetMapping("/qrcode")
    public JSONObject qrCode(@RequestParam String myId) {

        // 获取 token，参考文档 https://developers.weixin.qq.com/doc/offiaccount/Basic_Information/Get_access_token.html
        String accessTokenUrl = String.format(ACCESS_TOKEN_URL, properties.getAppId(), properties.getAppSecret());
        String tokenJson = HttpUtil.get(accessTokenUrl);
        JSONObject token = JSONUtil.parseObj(tokenJson);
        log.info("【token】= {}", token);

        if (token.containsKey("errcode")) {
            throw new ServiceException(CommonResultCode.INTERNAL_SERVER_ERROR, token);
        }

        // 现在
        long nowTimeStamp = System.currentTimeMillis();
        // 1.判断当前的有效期，是否有效
        if ((enableTimestamp - transitionTime) < nowTimeStamp) {
            System.out.println("过期了：重新请求---");
            // 获取 ticket，参考文档 参考文档 https://developers.weixin.qq.com/doc/offiaccount/Account_Management/Generating_a_Parametric_QR_Code.html
            String ticketUrl = String.format(NEW_TICKET_URL, token.getStr("access_token"));
            String ticketObj = HttpUtil.get(ticketUrl);
            JSONObject jsonObject = JSONUtil.parseObj(ticketObj);
            System.out.println("ticketObj => " + ticketObj);
            ticket = jsonObject.getStr("ticket");
            enableTimestamp += nowTimeStamp + jsonObject.getInt("expires_in") * 1000;
        }

        // 获取 ticket，参考文档 参考文档 https://developers.weixin.qq.com/doc/offiaccount/Account_Management/Generating_a_Parametric_QR_Code.html
        String ticketUrl = String.format(TICKET_URL, token.getStr("access_token"));
        String ticketJson = HttpUtil.post(ticketUrl,
                "{\"action_name\": \"QR_LIMIT_STR_SCENE\", \"action_info\": {\"scene\": {\"scene_str\": " + myId + "}}}"
        );
        JSONObject qrTicketObj = JSONUtil.parseObj(ticketJson);

        if (!qrTicketObj.containsKey("ticket")) {
            throw new ServiceException("无 ticket 信息");
        }

        log.info("【qrTicketObj】= {}", qrTicketObj);

        // 获取二维码图片信息，参考文档 https://developers.weixin.qq.com/doc/offiaccount/Account_Management/Generating_a_Parametric_QR_Code.html
        String qrCodeUrl = String.format(QR_CODE_URL, qrTicketObj.getStr("ticket"));
        //return "redirect:" + qrCodeUrl;

        JSONObject result = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("ticket", qrTicketObj);
        data.put("qrCodeUrl", qrCodeUrl);
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    List<String> subscribe_arr = new ArrayList<>();

    /**
     * 回调处理
     */
    // 监听post方法，微信服务器会将信息发给我
    @RequestMapping("/wxCallback")
    public void handlePost(HttpServletRequest request, HttpServletResponse response) throws Exception {
        System.out.println("post-home:" + request.getQueryString());
        if (request.getQueryString() == null) {
            return;
        }
        String signature = request.getParameter("signature");
        String echostr = request.getParameter("echostr");
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");
        String relStr = getValidateStr(request);
        if (relStr.equals(signature)) {
            System.out.println("信息来自微信服务器--");
            // 提取信息
            String xmlData = getXMLStr(request);
            System.out.println("xmlData:" + xmlData);
            if (xmlData.equals("")) { // 配置回调验证服务器存在 GET, 固定返回echostr
                response.setCharacterEncoding("UTF-8");
                System.out.println("echostr => " + echostr);
                response.getWriter().write(echostr);
                return;
            }
            /** 微信服务器返回了的xml格式数据
             <xml>
             <ToUserName><![CDATA[gh_b3958963bb18]]></ToUserName>
             <FromUserName><![CDATA[od4SM6Y8InFQGTfBjsiMRhkteIAE]]></FromUserName>
             <CreateTime>1648658404</CreateTime>
             <MsgType><![CDATA[text]]></MsgType>
             <Content><![CDATA[3]]></Content>
             <MsgId>23603117248352202</MsgId>
             </xml>
             */
            // 通过工具解析xml数据
            Map<String, String> jsData = xmlToHashMap(xmlData);
            System.out.println("jsData:" + jsData);
            // 再次优化数据
            Map<String, String> msgObj = getObjData(jsData);
            String eventKey = msgObj.get("EventKey");
            if (eventKey != null && !eventKey.equals("")) {
                if (eventKey.contains("qrscene_")) {
                    eventKey = eventKey.split("_")[1];
                }
                eventKey = eventKey + " => " + jsData.get("FromUserName");
                System.out.println("msgObj:" + msgObj);
                int index = subscribe_arr.indexOf(eventKey);
                if (index == -1) {
                    subscribe_arr.add(eventKey);
                    // 往列表尾部添加值
                    redisTemplate.opsForList().leftPush("subscribe_list", eventKey);
                    // 判断值是否在列表中
                    //查询list中指定范围的内容
                    List<String> list = redisTemplate.opsForList().range("subscribe_list", 0, -1);
                    System.out.println("subscribe_list => " + list);
                    if (list != null) {
                        log.info("当前登录用户数量: " + list.size());
                    }
                }
            }
            // 如果长度超过100，登录人数过多时
            /*if (this.subscribe_arr.size() > 100) {
                int start = this.subscribe_arr.size() - 100;
                this.subscribe_arr = this.subscribe_arr.subList(start, this.subscribe_arr.size());
            }*/
            // 回复信息给 微信服务器
            String content = "";
            if (msgObj.get("MsgType").equals("text")) {
                if (msgObj.get("Content").equals("9")) {
                    content = "9";
                } else if (msgObj.get("Content").contains("爱")) {
                    content = "ok";
                } else {
                    content = "";
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(content);
                    return;
                }
            } else if (msgObj.get("MsgType").equals("event")) {
                content = "event事件";
                if (msgObj.get("Event").equals("SCAN")) {
                    content = "登录成功\n欢迎使用叮当HealthGPT!";
                } else if (msgObj.get("Event").equals("subscribe")) {
                    content = "你好，欢迎关注叮当HealthGPT!";
                }
                if (msgObj.get("Event").equals("unsubscribe")) {
                    content = "取消关注";
                }
            } else {
                content = "其他信息来源！";
            }
            // 根据来时的信息格式，重组返回。(注意中间不能有空格)
            String msgStr = "<xml>" +
                    "<ToUserName><![CDATA[" + msgObj.get("FromUserName") + "]]></ToUserName>" +
                    "<FromUserName><![CDATA[" + msgObj.get("ToUserName") + "]]></FromUserName>" +
                    "<CreateTime>" + System.currentTimeMillis() + "</CreateTime>" +
                    "<MsgType><![CDATA[text]]></MsgType>" +
                    "<Content><![CDATA[" + content + "]]></Content>" +
                    "</xml>";
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(msgStr);
        } else {
            System.out.println("信息来历不明--");
        }
    }

    @PostMapping("/isSubscribe")
    public JSONObject isSubscribe(@RequestBody WechatLoginParam wechatLoginParam) {
        System.out.println("wechatLoginParam => " + wechatLoginParam);
        System.out.println("subscribe_arr => " + subscribe_arr);
        String myId = wechatLoginParam.getMyId();
        int index = -1;
        index = indexOfContains(subscribe_arr, myId);
        if (index == -1) {
            myId = "qrscene_" + myId;
            index = indexOfContains(subscribe_arr, myId);
        }
        System.out.println("index: " + index);
        boolean hasOne = index != -1;
        JSONObject result = new JSONObject();
        if (hasOne) {
            System.out.println("验证通过，登录成功------------------");
            result.put("code", 200);
            result.put("msg", "验证通过，登录成功");
            return result;
        }
        result.put("code", 502);
        result.put("msg", "-1");
        return result;
    }

    public static int indexOfContains(List<String> list, String target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(target)) {
                return i;
            }
        }
        return -1;
    }

    public String getValidateStr(HttpServletRequest request) {
        String token = properties.getToken();
        System.out.println("我的token:" + token);
        String signature = request.getParameter("signature");
        String echostr = request.getParameter("echostr");
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");

        // 将 token, timestamp, nonce 三项按照字典排序
        List<String> arr = Arrays.asList(token, timestamp, nonce);
        Collections.sort(arr);
        System.out.println("sort-arr" + arr);
        String arrStr = String.join("", arr);

        // 然后通过SHA1加密
        String relStr = sha1(arrStr);
        System.out.println("relStr" + relStr);
        return relStr;
    }

    public String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static HashMap<String, String> xmlToHashMap(String xml) {
        HashMap<String, String> hashMap = new HashMap<>();
        try {
            InputStream inputStream = new ByteArrayInputStream(xml.getBytes());
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            DefaultHandler handler = new DefaultHandler() {
                String currentElement;
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    currentElement = qName;
                }
                public void characters(char[] ch, int start, int length) throws SAXException {
                    String value = new String(ch, start, length).trim();
                    if (!value.isEmpty()) {
                        hashMap.put(currentElement, value);
                    }
                }
            };
            saxParser.parse(inputStream, handler);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hashMap;
    }

    // 解析xml字符串，返回Document对象
    private Document parseXMLString(String xmlString) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xmlString.getBytes());
        InputSource inputSource = new InputSource(inputStream);
        return (Document) builder.parse(inputSource);
    }

    // 获取XML字符串
    private String getXMLStr(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        BufferedReader reader = request.getReader();
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    // 重组返回数据，返回Map对象
    private Map<String, String> getObjData(Map<String, String> xmlData) {
        Map<String, String> objData = new HashMap<>();
        for (Map.Entry<String, String> entry : xmlData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // 对需要特殊处理的字段进行处理
            if (key.equals("ToUserName")) {
                objData.put("ToUserName", value);
            } else if (key.equals("FromUserName")) {
                objData.put("FromUserName", value);
            } else if (key.equals("CreateTime")) {
                objData.put("CreateTime", value);
            } else if (key.equals("MsgType")) {
                objData.put("MsgType", value);
            } else if (key.equals("Content")) {
                objData.put("Content", value);
            } else if (key.equals("Event")) {
                objData.put("Event", value);
            } else if (key.equals("EventKey")) {
                objData.put("EventKey", value);
            } else if (key.equals("Ticket")) {
                objData.put("Ticket", value);
            } else if (key.equals("MsgId")) {
                objData.put("MsgId", value);
            }
        }
        return objData;
    }

    private Object processEvent(Map<String, String> map) {
        String event = map.get("Event");
        EventType eventType = EventType.build(event);
        switch (eventType) {
            case SCAN:
                log.info("微信号：{} 在 {} 扫码登录", map.get("FromUserName"), new DateTime(Long.parseLong(map.get("CreateTime") + "000")));
                getUserInfo(map.get("FromUserName"));
                break;
            case SUBSCRIBE:
                log.info("微信号：{} 在 {} 关注，与自己系统用户绑定关系", map.get("FromUserName"), new DateTime(Long.parseLong(map.get("CreateTime") + "000")));
                getUserInfo(map.get("FromUserName"));
                break;
            case UNSUBSCRIBE:
                log.info("微信号：{} 在 {} 取关，需要解绑用户关系", map.get("FromUserName"), new DateTime(Long.parseLong(map.get("CreateTime") + "000")));
                getUserInfo(map.get("FromUserName"));
                break;
            case OTHER:
            default:
                log.info("【map】= {}", map);
                break;
        }
        return R.success();
    }

    /**
     * 获取用户信息，参考文档 https://developers.weixin.qq.com/doc/offiaccount/User_Management/Get_users_basic_information_UnionID.html#UinonId
     */
    private void getUserInfo(String openId) {
        // 获取 token
        String accessTokenUrl = String.format(ACCESS_TOKEN_URL, properties.getAppId(), properties.getAppSecret());
        String tokenJson = HttpUtil.get(accessTokenUrl);
        JSONObject token = JSONUtil.parseObj(tokenJson);

        // 获取 userInfo
        String userInfoUrl = String.format(USER_INFO_URL, token.getStr("access_token"), openId);
        String userInfoJson = HttpUtil.get(userInfoUrl);
        JSONObject userInfo = JSONUtil.parseObj(userInfoJson);
        log.info("【userInfo】= {}", userInfo);
    }

    //    oauth/wechat_mp/callback
//    @RequestMapping("/")
//    @ResponseBody
//    public Object callback(HttpServletRequest request, String echostr) {
//        String type = "";
//        Map<String, String> map = Maps.newHashMap();
//        try {
//            // 读取输入流
//            SAXReader reader = new SAXReader();
//            Document document = reader.read(request.getInputStream());
//            // 得到xml根元素
//            Element root = document.getRootElement();
//
//            XmlUtil.parserXml(root, map);
//            log.info("【map】= {}", map);
//
//            String msgType = map.get("MsgType");
//            if (StrUtil.isNotBlank(msgType)) {
//                type = msgType;
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        MsgType msgType = MsgType.build(type);
//
//        switch (msgType) {
//            case EVENT:
//                return processEvent(map);
//            case BINDING:
//                // 配置回调接口时，微信官方校验时，执行此逻辑
//                // 测试直接返回，生产环境需要根据 token 计算，比对结果，一切正确之后，返回 echostr
//                // 参考文档：https://developers.weixin.qq.com/doc/offiaccount/Getting_Started/Getting_Started_Guide.html
//                return echostr;
//            case OTHER:
//            default:
//                return R.success();
//        }
//    }

}
