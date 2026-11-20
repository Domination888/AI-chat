package org.example.aichat.release;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CancelCauseTest {
 @Test void firstSourceSurvivesLaterDisconnectAndTimeout(){var c=new OpenAiTransport.Control();c.stop(CancelCause.ACCOUNT_SUSPENDED);c.stop(CancelCause.CLIENT_DISCONNECTED);c.stop(CancelCause.REQUEST_TIMEOUT);assertEquals(CancelCause.ACCOUNT_SUSPENDED,c.cancellationCause());assertEquals("account_suspended",ServiceFailureCategory.classify(new java.io.IOException(),c));}
 @Test void eachExplicitSourceHasAccurateFixedCategory(){for(var cause:CancelCause.values()){var c=new OpenAiTransport.Control();c.stop(cause);assertTrue(c.stopped.get());assertEquals(cause.category(),ServiceFailureCategory.classify(new java.io.IOException(),c));}}
 @Test void untouchedControlStillClassifiesUpstreamAndHasNoCancellation(){var c=new OpenAiTransport.Control();assertNull(c.cancellationCause());assertFalse(c.stopped.get());assertEquals("upstream_auth_failed",ServiceFailureCategory.classify(new UpstreamHttpFailure(401,false),c));}
}
