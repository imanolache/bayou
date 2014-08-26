package bayou.http;

import bayou.form.FormData;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

// app cannot temper with this req, which is important.
//    we need to examine the original request info later during response mod.
// tho this class is similar to HttpRequestImpl, if we do subclass, app could cast then modify.
class ImplHttpRequest implements HttpRequest
{
    InetAddress ip;
    boolean isHttps;
    String method;
    String uri;
    final HeaderMap headers = new HeaderMap();
    HttpEntity entity;
    boolean sealed; // if sealed, request is good

    ImplHttpRequest()
    {
    }

    void seal()
    {
        headers.freeze();
        sealed = true;
    }




    @Override
    public InetAddress ip()
    {
        return ip;
    }

    @Override
    public boolean isHttps()
    {
        return isHttps;
    }

    @Override
    public String method()
    {
        return method;
    }

    @Override
    public String uri()
    {
        return uri;
    }

    volatile FormData uriFormData_volatile; // lazy; derived from `uri`
    // FormData is not immutable, so we must be careful with safe publication
    FormData uriFormData() throws Exception
    {
        FormData f = uriFormData_volatile;
        if(f==null)
            uriFormData_volatile = f = FormData.parse(uri);
        return f;
    }

    @Override
    public String uriPath()
    {
        try
        {
            return uriFormData().action();
        }
        catch (Exception e)
        {
            return HttpRequest.super.uriPath();
        }
    }

    @Override
    public String uriParam(String name)
    {
        try
        {
            return uriFormData().param(name);
        }
        catch (Exception e)
        {
            return null;
        }
    }


    @Override
    public HeaderMap headers()
    {
        return headers; // app cannot modify it - freeze() was called
    }

    Map<String,String> cookieMap; // lazy, immutable, derived from headers[Cookie]
    @Override
    public Map<String,String> cookies()
    {
        Map<String,String> map = cookieMap;
        if(map==null)
        {
            String hCookie = headers.get(Headers.Cookie);
            map = Cookie.parseCookieHeader(hCookie); // no throw. ok if hCookie==null
            map = Collections.unmodifiableMap(map);  // immutable with final ref; publication is safe
            cookieMap = map;
        }
        return map;
    }


    public HttpEntity entity()
    {
        return entity;
    }


    int httpMinorVersion=-1;   // 0 or 1. -1 if parse error.

    @Override
    public String httpVersion()
    {
        return (httpMinorVersion==0) ? "HTTP/1.0" : "HTTP/1.1";
    }




    // fix ip/isHttps based on X-Forwarded- headers
    void fixForward(int xForwardLevel)
    {
        assert xForwardLevel>=0;
        if(xForwardLevel==0)
            return;

        // if xForwardLevel>0, and X-Forwarded-For is not as expected, our load balancer is screwed up.
        // no need to check X-Forwarded-Proto in that case either, which is probably not trustworthy too.
        // however we don't raise error or make request as bad; just ignore X-Forwarded- headers.

        String xff = headers.get("X-Forwarded-For");
        if(xff==null)
            return;

        // xff contains N IPs, separated by comma. N>=1. each IP can be v4 or v6.
        // number the rightmost one the 1st IP, the leftmost one the N-th.
        // pick the xForwardLevel-th IP. if xForwardLevel>N, pick the N-th.
        xff = pick(xff, xForwardLevel);
        // it must be a valid IPv4 or IPv6 address. domain names are not allowed.
        // we assume that it's from our trusted load balancer, so it's a valid IP.
        // otherwise, InetAddress.getByName() may block. todo: do our validation here just to be sure.
        try
        {
            ip = InetAddress.getByName(xff); // we don't want this to block!!
        }
        catch (Exception e)
        {
            return;
        }

        String xfp = headers.get("X-Forwarded-Proto");
        if(xfp==null)
            return;

        // it's unclear what xfp contains. probably just one protocol, e.g. "https".
        // let's assume here that it can be a comma separated list as well, e.g. "http, https, http"
        // our pick() works even if N=1 and xForwardLevel>1.
        xfp = pick(xfp, xForwardLevel);
        if(xfp.equalsIgnoreCase("https"))
            isHttps=true;
        else if(xfp.equalsIgnoreCase("http"))
            isHttps=false;
        else
            ; // ignore
    }

    static String pick(String str, int level)
    {
        int end=str.length();
        while(true)
        {
            int comma = str.lastIndexOf(',', end-1);
            if(comma==-1 || level==1)
                return str.substring(comma+1, end).trim();
            --level;
            end = comma;
        }
    }




    // ---------------------------------------------------------------------------------------

    long timeReceived;

    // once the response is being written, body can no longer be read.
    // so we don't support currently reading req and writing response; such app is rare;
    // most clients are single threaded and can't handle that anyway.
    boolean responded;

    // "HTTP/1.1 100 Continue"
    // 0: not expected, don't send.
    // 1: expected, not sent yet
    // 2: tried to send, but failed
    // 3: sent successfully.
    int state100;

    // 0: no entity, no body
    // 1: has body, has not been read
    // 2: body is being read
    // 3: entire body is read
    // if body length is known to be 0, set state=3 from the beginning.
    int stateBody;

}