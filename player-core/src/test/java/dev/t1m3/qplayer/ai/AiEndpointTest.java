package dev.t1m3.qplayer.ai;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiEndpointTest {
    @Test
    public void transportFailuresRemainDistinctFromFormattingAndAuthentication() {
        Throwable dns = new IOException("request failed", new java.net.UnknownHostException("api.deepseek.com"));
        assertTrue(AiEndpoint.isConnectionFailure(dns));
        assertTrue(AiEndpoint.connectionFailureMessage(dns).contains("域名解析失败"));
        assertTrue(AiEndpoint.connectionFailureMessage(new java.net.SocketException("Software caused connection abort"))
                .contains("网络连接中断"));
        assertTrue(AiEndpoint.connectionFailureMessage(new java.net.SocketTimeoutException()).contains("请求超时"));
        assertFalse(AiEndpoint.isConnectionFailure(new IOException("HTTP 401 invalid API key")));
        assertFalse(AiEndpoint.isConnectionFailure(new javax.net.ssl.SSLHandshakeException("certificate invalid")));
        assertFalse(AiEndpoint.isConnectionFailure(new AiClient.ResponseFormatException("empty songs")));
    }
}
