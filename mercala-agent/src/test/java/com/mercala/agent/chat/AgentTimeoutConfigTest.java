package com.mercala.agent.chat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the ordering of two timeouts that have to stay in a specific relationship.
 *
 * <p>Tomcat's default async timeout is 30 seconds. A streamed agent turn that invokes tools
 * routinely runs longer, so on the default the container aborts the response before the
 * application's own timeout fires — the client receives a truncated SSE stream and none of
 * the sanitised ERROR-frame handling runs. The failure looks like a network fault rather
 * than a timeout, which makes it needlessly hard to diagnose.
 *
 * <p>The relationship is documented in {@code application.yml}, but a comment cannot stop
 * someone raising the stream timeout for a slow model and leaving the async timeout behind.
 * This fails the build instead.
 *
 * <p><strong>There is a third timeout this test cannot see.</strong> nginx applies
 * {@code proxy_read_timeout 180s} to the SSE route, and it is the outermost of the chain:
 *
 * <pre>
 *   mercala.agent.stream-timeout       120s   application
 *   spring.mvc.async.request-timeout   150s   servlet container
 *   proxy_read_timeout                 180s   nginx  (devops/ansible/templates/nginx.conf.j2)
 * </pre>
 *
 * <p>It lives in an Ansible template rather than Spring configuration, so raising the
 * application timeout past 180s would pass this test and still produce truncated streams in
 * production. If you change the values here, change the template too.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "spring.kafka.bootstrap-servers=localhost:59092",
})
class AgentTimeoutConfigTest {

    @Value("${spring.mvc.async.request-timeout}")
    private Duration asyncRequestTimeout;

    @Value("${mercala.agent.stream-timeout}")
    private Duration streamTimeout;

    @Test
    void containerAsyncTimeoutOutlivesTheApplicationStreamTimeout() {
        assertThat(asyncRequestTimeout)
                .as("the container must not abort the response before the application can "
                        + "terminate the turn with an ERROR frame")
                .isGreaterThan(streamTimeout);
    }

    @Test
    void thereIsEnoughHeadroomToEmitTheTerminalFrame() {
        Duration headroom = asyncRequestTimeout.minus(streamTimeout);

        // A second or two would technically satisfy the ordering while leaving no room to
        // serialise and flush the final frame over a slow connection.
        assertThat(headroom)
                .as("leave room for the ERROR frame to actually reach the client")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(10));
    }
}
