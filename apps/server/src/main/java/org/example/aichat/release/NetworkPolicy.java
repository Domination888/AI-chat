package org.example.aichat.release;

import java.net.*;
import java.util.Set;

public final class NetworkPolicy {
  private NetworkPolicy() {}
  public static final class AddressRejected extends IllegalArgumentException {
    private final boolean possibleFakeDns;
    AddressRejected(String message,boolean possibleFakeDns){super(message);this.possibleFakeDns=possibleFakeDns;}
    public boolean possibleFakeDns(){return possibleFakeDns;}
  }
  public static URI publicPage(String value) {
    URI uri=URI.create(value);
    if(uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("来源地址不合法");
    publicHttps(uri.getScheme()+"://"+uri.getRawAuthority());return uri;
  }
  public static URI publicHttps(String value) {
    URI uri=httpsAddress(value);
    try {
      var addresses=InetAddress.getAllByName(uri.getHost());
      for(var address:addresses){byte[] bytes=address.getAddress();if(bytes.length==4&&(bytes[0]&255)==198&&((bytes[1]&255)==18||(bytes[1]&255)==19))
        throw new AddressRejected("API 域名解析到了保留网段，可能由代理的 Fake-IP DNS 模式造成。请管理员为该域名配置真实公网 DNS 解析后重试；不会访问内网或保留地址。",true);}
      for(var address:addresses)if(!isPublic(address))throw new AddressRejected("API 地址不能访问本机、内网或保留网段",false);
    } catch(UnknownHostException e) { throw new IllegalArgumentException("API 域名无法解析"); }
    return uri;
  }
  public static URI offlineExternalHttps(String value) {
    URI uri=httpsAddress(value);
    String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if(host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) throw new IllegalArgumentException("外部 API 请填写公网 HTTPS 域名");
    // The local owner explicitly opted in to this host. Proxy/TUN DNS may return
    // synthetic IPv4 and IPv6 addresses; HTTPS still authenticates the hostname.
    // Cloud BYOK must continue to use publicHttps and its strict DNS validation.
    if(host.startsWith("[") || host.matches("[0-9.]+")) {
      try { if(!isPublic(InetAddress.getByName(host))) throw new IllegalArgumentException("API 地址不能访问本机、内网或保留网段"); }
      catch(UnknownHostException e) { throw new IllegalArgumentException("API 地址不合法"); }
    }
    return uri;
  }
  private static URI httpsAddress(String value) {
    URI uri=URI.create(value);
    if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) throw new IllegalArgumentException("仅支持公网 HTTPS API 地址（443 端口）");
    return uri;
  }
  public static boolean isPublic(InetAddress a) {
    if(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
    byte[] b=a.getAddress();
    if(b.length==16) return (b[0]&0xe0)==0x20 && !((b[0]&255)==0x20 && ((b[1]&255)==2 || ((b[1]&255)==1 && (((b[2]&255)==0 && (b[3]&255)==0) || ((b[2]&255)==0x0d && (b[3]&255)==0xb8))))); // only global-unicast 2000::/3
    int x=b[0]&255,y=b[1]&255;
    return x!=0 && x!=10 && x!=127 && x<224 && !(x==100 && y>=64 && y<=127) && !(x==169 && y==254) && !(x==172 && y>=16 && y<=31) && !(x==192 && (y==168 || y==0 || y==2)) && !(x==198 && (y==18 || y==19 || y==51)) && !(x==203 && y==0);
  }
  public static URI requireLoopback(String value) {
    var u=URI.create(value);
    if(!Set.of("http","https").contains(u.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]","::1").contains(u.getHost()) || u.getUserInfo()!=null) throw new IllegalArgumentException("离线模式只允许本机模型地址；外部 API 需要明确启用联网模式");
    return u;
  }
}
