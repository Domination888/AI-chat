package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

final class CompanionWorld {
  private final CompanionService companion;
  private final ObjectMapper json;
  private final PublicContent content;
  private final RuntimeMode mode;

  CompanionWorld(CompanionService companion, ObjectMapper json, PublicContent content, RuntimeMode mode) {
    this.companion = companion;
    this.json = json;
    this.content = content;
    this.mode = mode;
  }

  static String observationAge(String observed,Instant now){
    String prefix="计算当前时间："+now+"。";
    try{
      String value=observed.strip().replace(" UTC","Z");
      Instant time;try{time=Instant.parse(value);}catch(java.time.format.DateTimeParseException e){time=OffsetDateTime.parse(value).toInstant();}
      long seconds=Duration.between(time,now).getSeconds();
      if(seconds<0)return prefix+"观测时间在未来，无法可靠计算观测年龄；不得称为几分钟前或几小时前。";
      return prefix+"观测距当前"+(seconds/60)+"分钟（不足一分钟部分舍去）。直接使用此年龄，不凭缓存期限估算；10分钟查询缓存不代表观测已过去一小时。";
    }catch(Exception e){return prefix+"来源未给出可解析且带时区的观测时间，年龄未知，不推测几分钟前或几小时前。";}
  }
  ObjectNode snapshot(int user){
    var s=companion.settings(user);var out=json.createObjectNode();var now=ZonedDateTime.now(ZoneId.of(s.path("timezone").asText()));
    out.put("localDateTime",now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss")));out.put("timezone",now.getZone().getId());out.put("location",s.path("city").asText().isBlank()?(s.hasNonNull("latitude")?("ip".equals(s.path("locationSource").asText())?"IP 粗定位附近":"设备定位附近"):""):s.path("city").asText());out.put("locationSource",s.path("locationSource").asText());out.put("weather",s.path("weather").asText());
    out.put("weatherLocation",s.path("weatherLocation").asText());out.set("weatherSource",s.path("weatherSource").deepCopy());
    out.put("weatherObservedLocal",localWeatherObservation(s.path("weather").asText(),now.getZone()));
    boolean weatherNetwork=s.path("worldNetworkEnabled").asBoolean(), hasWeather=!s.path("weather").asText().isBlank();
    out.put("weatherNetworkEnabled",weatherNetwork);out.put("weatherCheckedAt",s.path("weatherCheckedAt").asText());out.put("weatherUpdatedAt",s.path("weatherUpdatedAt").asText());
    out.put("weatherFetchState",weatherNetwork?(hasWeather?"cached":"unavailable"):"network_disabled");
    out.put("weatherLastRefreshStatus",s.has("weatherRefreshSucceeded")?(s.path("weatherRefreshSucceeded").asBoolean()?"succeeded":"failed"):"unknown");
    if(!weatherNetwork)out.put("weatherStatus","联网查询已关闭，未联网刷新。"+(hasWeather?"已有观测仅作历史参考。":"尚无天气观测。"));
    else if(s.has("weatherRefreshSucceeded")&&!s.path("weatherRefreshSucceeded").asBoolean())out.put("weatherStatus","上次刷新未成功："+s.path("weatherStatus").asText());
    else out.put("weatherStatus",hasWeather?"显示已保存天气观测；当前查看未联网刷新。":"尚未取得天气观测，可点击“刷新天气”查询。");
    var solar=new com.nlf.calendar.Solar(now.getYear(),now.getMonthValue(),now.getDayOfMonth(),now.getHour(),now.getMinute(),now.getSecond());var lunar=solar.getLunar();
    out.put("lunarDate",lunar.getYearInGanZhi()+"年"+lunar.getMonthInChinese()+"月"+lunar.getDayInChinese());
    var previous=lunar.getPrevJieQi(true);var next=lunar.getNextJieQi(true);
    out.put("solarTerm",!lunar.getJieQi().isBlank()?lunar.getJieQi()+"（今日）":(previous==null?"":previous.getName())+(next==null?"":"；下个节气："+next.getName()+"（"+next.getSolar().toYmd()+"）"));
    var festivals=out.putArray("festivals");var names=new LinkedHashSet<String>(solar.getFestivals());names.addAll(lunar.getFestivals());names.forEach(festivals::add);
    if(s.hasNonNull("latitude")){out.set("latitude",s.get("latitude"));out.set("longitude",s.get("longitude"));}return out;
  }
  static String localWeatherObservation(String observation,ZoneId zone){
    var time=java.util.regex.Pattern.compile("观测时间 ([0-9T:.\\-]+) UTC").matcher(observation);
    if(!time.find())return "";
    try{return LocalDateTime.parse(time.group(1)).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))+"（"+zone.getId()+"）";}
    catch(java.time.DateTimeException invalidLegacyObservation){return "";}
  }
  ObjectNode locate(int user,JsonNode input,boolean ip)throws Exception {
    var s=companion.settings(user);double lat,lon;String source="device";
    if(ip){
      if(mode.cloud())throw new IllegalArgumentException("商业版不能用服务器 IP 判断你的位置，请使用设备定位或手动填写城市");
      requireWorldNetwork(s);var estimate=json.readTree(content.get("https://ipwho.is/?lang=zh"));
      if(!estimate.path("success").asBoolean() || !estimate.path("latitude").isNumber() || !estimate.path("longitude").isNumber())throw new IllegalStateException("IP 粗定位暂不可用");
      lat=estimate.path("latitude").asDouble();lon=estimate.path("longitude").asDouble();source="ip";s.put("city",estimate.path("city").asText());
    }else{
      if(!input.path("latitude").isNumber() || !input.path("longitude").isNumber())throw new IllegalArgumentException("坐标格式不正确");
      lat=input.path("latitude").asDouble();lon=input.path("longitude").asDouble();s.put("city","");
    }
    if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IllegalArgumentException("坐标无效，请重新定位");
    // City precision only; external reverse lookup/weather is separately enabled by the owner.
    lat=Math.round(lat*100.0)/100.0;lon=Math.round(lon*100.0)/100.0;
    s.put("latitude",lat);s.put("longitude",lon);s.put("locationSource",source);s.put("weather","");s.put("weatherStatus","");s.remove(List.of("weatherSource","weatherLocation","weatherCheckedAt","weatherUpdatedAt","weatherRefreshSucceeded"));
    if(input.has("timezone")){try{ZoneId.of(input.path("timezone").asText());s.put("timezone",input.path("timezone").asText());}catch(Exception e){throw new IllegalArgumentException("时区不正确");}}
    if(s.path("worldNetworkEnabled").asBoolean()){
      try{
        var reverse=json.readTree(content.get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&accept-language=zh-CN&zoom=10&lat="+lat+"&lon="+lon));
        var address=reverse.path("address");for(String key:List.of("city","town","county","state"))if(!address.path(key).asText().isBlank()){s.put("city",address.path(key).asText());break;}
      }catch(Exception ignored){/* Coordinates remain usable without a city provider. */}
    }
    companion.persist(user,s);return s.path("worldNetworkEnabled").asBoolean()?refreshWeather(user):snapshot(user);
  }

  private String weatherLabel(int code){
    if(code==0)return "晴";if(code<=3)return "多云";if(code<=48)return "雾";if(code<=57)return "毛毛雨";if(code<=67)return "雨";if(code<=77)return "雪";if(code<=82)return "阵雨";if(code<=86)return "阵雪";return "雷雨";
  }
  ObjectNode manualLocation(int user,JsonNode input){
    var s=companion.save(user,input);s.remove(List.of("latitude","longitude","weatherSource","weatherLocation","weatherCheckedAt","weatherUpdatedAt","weatherRefreshSucceeded"));s.put("locationSource",s.path("city").asText().isBlank()?"unset":"manual");s.put("weather","");s.put("weatherStatus","");companion.persist(user,s);
    return s.path("worldNetworkEnabled").asBoolean()&&!s.path("city").asText().isBlank()?refreshWeather(user):snapshot(user);
  }
  void clearLocation(int user){var s=companion.settings(user);s.remove(List.of("latitude","longitude","weatherSource","weatherLocation","weatherCheckedAt","weatherUpdatedAt","weatherRefreshSucceeded"));s.put("city","");s.put("locationSource","unset");s.put("weather","");s.put("weatherStatus","");companion.persist(user,s);}
  ObjectNode refreshWeather(int user){
    var s=companion.settings(user);requireWorldNetwork(s);
    if(s.path("city").asText().isBlank()&&!s.hasNonNull("latitude"))throw new IllegalArgumentException("请先填写城市或使用设备定位");
    boolean succeeded=false;
    try{
      double lat,lon;String weatherLocation;
      if("manual".equals(s.path("locationSource").asText())||!s.hasNonNull("latitude")){
        String name=java.net.URLEncoder.encode(s.path("city").asText(),java.nio.charset.StandardCharsets.UTF_8);
        var matches=json.readTree(content.get("https://geocoding-api.open-meteo.com/v1/search?name="+name+"&count=1&language=zh&format=json")).path("results");
        if(!matches.isArray()||matches.isEmpty())throw new IllegalArgumentException("未找到该城市，请尝试城市常用名称或英文名，例如 杭州 或 London");
        var city=matches.get(0);if(!city.path("latitude").isNumber()||!city.path("longitude").isNumber())throw new java.io.IOException("Missing city coordinates");
        lat=city.path("latitude").asDouble();lon=city.path("longitude").asDouble();
        weatherLocation=city.path("name").asText()+" · "+city.path("admin1").asText()+" · "+city.path("country").asText();
      }else{lat=s.path("latitude").asDouble();lon=s.path("longitude").asDouble();weatherLocation=s.path("city").asText();}
      if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new java.io.IOException("Invalid city coordinates");
      lat=Math.round(lat*100.0)/100.0;lon=Math.round(lon*100.0)/100.0;
      String sourceUrl="https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code&timezone=UTC";var response=json.readTree(content.get(sourceUrl));var current=response.path("current");
      if(!current.path("temperature_2m").isNumber()||!current.path("weather_code").isNumber()||current.path("time").asText().isBlank())throw new java.io.IOException("Missing current weather");
      s.set("weatherSource",json.createObjectNode().put("url",sourceUrl).put("observedAt",current.path("time").asText()+" UTC"));
      s.put("weather",weatherLabel(current.path("weather_code").asInt())+"，"+current.path("temperature_2m").asText()+"°C（Open-Meteo，观测时间 "+current.path("time").asText()+" UTC）");s.put("weatherStatus","本次已联网取得天气观测，观测时间以数据来源为准。");s.put("weatherLocation",weatherLocation);s.put("weatherUpdatedAt",Instant.now().toString());succeeded=true;
    }catch(IllegalArgumentException e){s.put("weatherStatus",e.getMessage());}
    catch(Exception e){s.put("weatherStatus","天气服务暂不可用，请稍后点击“刷新天气”重试。已有观测仅作历史参考。");}
    s.put("weatherCheckedAt",Instant.now().toString());s.put("weatherRefreshSucceeded",succeeded);companion.persist(user,s);
    var result=snapshot(user);result.put("weatherFetchState",succeeded?"refreshed":"refresh_failed");
    result.put("weatherStatus",succeeded?s.path("weatherStatus").asText():"本次联网刷新未成功："+s.path("weatherStatus").asText());return result;
  }
  CompanionService.WorldAnswer worldAnswer(int user,String message){
    var s=companion.settings(user);if(!s.path("worldEnabled").asBoolean()||!message.matches("(?s).*(天气|温度|几点|当地时间|农历|节气|日期|几号).*"))return new CompanionService.WorldAnswer("",List.of());
    var world=snapshot(user);String fetch="本轮没有请求天气查询，未联网刷新。";
    if(message.matches("(?s).*(天气|温度).*")){
      if(!s.path("worldNetworkEnabled").asBoolean())fetch="城市与天气联网查询已关闭；本轮未联网，未刷新天气。若有观测仅是历史缓存。";
      else if(s.path("city").asText().isBlank()&&!s.hasNonNull("latitude"))fetch="尚未设置城市或设备位置；本轮未联网查询天气。";
      else {
        boolean expired=true;try{expired=Instant.parse(s.path("weatherCheckedAt").asText()).isBefore(Instant.now().minusSeconds(600));}catch(Exception ignored){}
        boolean explicitRefresh=message.matches("(?s).*(刷新|重新查询|再次查询|再查|重新查|更新天气|查询最新|查询实时).*" );
        if(expired||explicitRefresh){
          world=refreshWeather(user);
          fetch="refreshed".equals(world.path("weatherFetchState").asText())?"本轮已联网查询成功，取得提供方的观测；不代表观测时间就是此刻。":"本轮已尝试联网，但刷新失败，未取得新的天气观测；原有观测仅作历史参考。";
        }else fetch="本轮使用10分钟内的查询缓存，未联网刷新。"+("failed".equals(world.path("weatherLastRefreshStatus").asText())?"上次刷新失败，已有观测仅作历史参考。":"不能称为刚刚重新查询或数据已更新。");
      }
    }
    String context="\n【本轮当地环境资料】\n本轮查询行为："+fetch+"\n城市："+world.path("location").asText()+"\n当前设备时钟的当地时间："+world.path("localDateTime").asText()+"（"+world.path("timezone").asText()+"）\n农历："+world.path("lunarDate").asText()+"\n节气："+world.path("solarTerm").asText()+"\n天气观测："+(world.path("weather").asText().isBlank()?"未取得天气观测":world.path("weather").asText())+"\n同一观测的当地时间："+(world.path("weatherObservedLocal").asText().isBlank()?"来源未提供可转换的观测时间":world.path("weatherObservedLocal").asText())+"\n观测/缓存状态："+world.path("weatherStatus").asText()+"\n只根据以上资料直接回答。明确区分本轮联网成功、缓存未刷新和刷新失败；未联网或失败时绝不能声称刚刚查询、实时更新或数据已更新。未联网或刷新失败时，已有温度必须连同原观测时间及历史缓存限定说明，设备时钟是本轮当前时间，不能称作缓存记录的时间，也不能当观测时间。UTC 与同一观测的当地时间是同一时刻的不同表示，回答当地钟点直接用已提供的转换结果，不把 UTC 钟点说成当地钟点。已提供的当地时间可以回答；没有天气观测时说明未取得，不猜测。角色语气不能替代答案，不输出场景标签。\n";
    context+="\n"+observationAge(world.path("weatherSource").path("observedAt").asText(),Instant.now())+"\n";
    context+="\n网页搜索状态与天气 API 查询状态相互独立：网页搜索未找到资料，不代表本轮 Open-Meteo 天气 API 未取得观测。天气数值只依据上述 Open-Meteo 观测及其自身来源，不得引用无关网页搜索卡片（例如城市主页）为天气数值作证；无原始来源链接的历史缓存仍须明确说明并提示刷新补齐。\n";
    var sources=new ArrayList<Map<String,Object>>();
    if(message.matches("(?s).*(天气|温度).*")&&!world.path("weather").asText().isBlank()){
      var source=world.path("weatherSource");
      if(source.path("url").asText().isBlank())context+="\n历史缓存未保存原始观测来源链接，请刷新天气补齐；不要将搜索网页当作该观测的来源。\n";
      else sources.add(Map.of("title","Open-Meteo · "+source.path("observedAt").asText()+" · "+fetch,"url",source.path("url").asText(),"snippet",world.path("weather").asText()+"；"+fetch,"pageRead",false,"excerpts",List.of()));
    }
    return new CompanionService.WorldAnswer(context,sources);
  }
  private void requireWorldNetwork(JsonNode s){if(!s.path("worldNetworkEnabled").asBoolean())throw new IllegalArgumentException("请先开启城市与天气联网查询");}
}
