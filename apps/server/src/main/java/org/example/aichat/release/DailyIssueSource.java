package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class DailyIssueSource {
  private static final String ORIGIN="https://daily.juya.uk";
  private static final Pattern ITEM=Pattern.compile("(?ms)^### \\[(.+?)]\\((https?://[^\\s)]+)\\).*?\\n>\\s*(.*?)\\n\\n(.*?)(?=\\n---\\n|\\n### |\\n## |\\z)");
  private final StoryStore stories;
  private final PublicContent content;
  private final ObjectMapper json;
  private final WebAccess web;

  DailyIssueSource(StoryStore stories, PublicContent content, ObjectMapper json, WebAccess web) {
    this.stories = stories;
    this.content = content;
    this.json = json;
    this.web = web;
  }

  private record Cached(JsonNode digest,Instant fetched){}
  private final Map<String,Cached> cache=new ConcurrentHashMap<>();
  private JsonNode manifest(int user){var rows=stories.db().queryForList("SELECT manifest_json FROM companion_skill WHERE user_id=? AND name='ai-daily-juya'",user);try{return rows.isEmpty()?json.createObjectNode():json.readTree(rows.get(0).get("manifest_json").toString());}catch(Exception e){throw new IllegalStateException("日报技能配置无法读取",e);}}
  private String fetchText(String url)throws Exception{return url.startsWith(ORIGIN+"/")?content.get(url):web.get(url,false);}
  synchronized JsonNode fetch(int user)throws Exception{
    var manifest=manifest(user);String rss=manifest.path("source").path("url").asText(ORIGIN+"/rss.xml");if(rss.isBlank())rss=ORIGIN+"/rss.xml";
    var source=java.net.URI.create(rss);if(!rss.startsWith(ORIGIN+"/"))NetworkPolicy.publicPage(rss);String origin=source.getScheme()+"://"+source.getRawAuthority();int maxItems=Math.max(1,Math.min(20,manifest.path("proactive").path("maxItems").asInt(12)));String key=rss+":"+maxItems;
    var cached=cache.get(key);if(cached!=null&&cached.fetched().plusSeconds(300).isAfter(Instant.now()))return cached.digest();
    var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setExpandEntityReferences(false);factory.setXIncludeAware(false);
    var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(fetchText(rss).getBytes(StandardCharsets.UTF_8)));
    var nodes=document.getElementsByTagName("item");Element latest=null;String date="";
    for(int i=0;i<nodes.getLength();i++){
      var item=(Element)nodes.item(i);var matcher=AiDailyService.DATE.matcher(text(item,"title")+" "+text(item,"link"));
      if(matcher.find())try{String d=LocalDate.parse(matcher.group()).toString();if(d.compareTo(date)>0 && !LocalDate.parse(d).isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))){date=d;latest=item;}}catch(java.time.format.DateTimeParseException ignored){}
    }
    if(latest==null)throw new IllegalStateException("日报来源尚未发布可读取的期刊");
    var digest=parseIssue(date,clean(text(latest,"title"),200),safeLink(text(latest,"link")).isBlank()?origin+"/issues/"+date+"/":safeLink(text(latest,"link")),origin,maxItems);
    if(cache.size()>100)cache.clear();cache.put(key,new Cached(digest,Instant.now()));return digest;
  }
  private JsonNode parseIssue(String date,String title,String sourceUrl,String origin,int maxItems)throws Exception {
    var digest=json.createObjectNode();digest.put("date",date);digest.put("title",title);digest.put("sourceUrl",sourceUrl);var items=digest.putArray("items");
    String markdown=fetchText(origin+"/markdown/"+date+".md");var match=ITEM.matcher(markdown);
    while(match.find() && items.size()<maxItems){String link=safeLink(match.group(2));items.addObject().put("title",clean(match.group(1),200)).put("link",link).put("summary",clean(match.group(3),520)).put("detail",clean(match.group(4).replaceAll("(?s)相关链接.*$",""),1200));}
    if(items.isEmpty())throw new IllegalStateException("日报正文格式已变化，未能解析条目；已保留上次日报");
    return digest;
  }
  synchronized JsonNode issue(int user,String date)throws Exception {
    // Fixed paths on the configured feed origin only; never a user-supplied URL.
    LocalDate requested=LocalDate.parse(date);
    if(requested.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1)))throw new IllegalArgumentException("日报期次尚未发布");
    var manifest=manifest(user);String rss=manifest.path("source").path("url").asText(ORIGIN+"/rss.xml");if(rss.isBlank())rss=ORIGIN+"/rss.xml";
    if(!rss.startsWith(ORIGIN+"/"))NetworkPolicy.publicPage(rss);
    var uri=java.net.URI.create(rss);String origin=uri.getScheme()+"://"+uri.getRawAuthority();int maxItems=Math.max(1,Math.min(20,manifest.path("proactive").path("maxItems").asInt(12)));
    String key=rss+":"+maxItems+":"+date;var cached=cache.get(key);
    if(cached!=null&&cached.fetched().plusSeconds(300).isAfter(Instant.now()))return cached.digest();
    var digest=parseIssue(date,"AI 日报 "+date,origin+"/issues/"+date+"/",origin,maxItems);
    if(cache.size()>100)cache.clear();cache.put(key,new Cached(digest,Instant.now()));return digest;
  }
  private static String text(Element element,String tag){var nodes=element.getElementsByTagName(tag);return nodes.getLength()==0?"":nodes.item(0).getTextContent().strip();}
  private static String clean(String raw,int max){String value=raw.replaceAll("<[^>]*>","").replaceAll("[\\r\\t]"," ").replaceAll("\\*\\*","").strip();return value.substring(0,Math.min(value.length(),max));}
  static String safeLink(String value){try{var uri=java.net.URI.create(value);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null?value:"";}catch(Exception e){return "";}}
}
