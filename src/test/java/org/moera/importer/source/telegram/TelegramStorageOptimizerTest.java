package org.moera.importer.source.telegram;

import java.lang.reflect.Method;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramStorageOptimizerTest {

    @Test
    void optimizesStorageWhenTelegramIsReady() {
        TelegramClient telegramClient = mock(TelegramClient.class);
        when(telegramClient.isReady()).thenReturn(true);
        TelegramStorageOptimizer optimizer = optimizer(telegramClient);

        optimizer.run();

        verify(telegramClient).optimizeStorage();
    }

    @Test
    void skipsOptimizationUntilTelegramIsReady() {
        TelegramClient telegramClient = mock(TelegramClient.class);
        TelegramStorageOptimizer optimizer = optimizer(telegramClient);

        optimizer.run();

        verify(telegramClient, never()).optimizeStorage();
    }

    @Test
    void containsOptimizationFailureSoLaterRunsRemainScheduled() {
        TelegramClient telegramClient = mock(TelegramClient.class);
        when(telegramClient.isReady()).thenReturn(true);
        doThrow(new IllegalStateException("TDLight unavailable")).when(telegramClient).optimizeStorage();
        TelegramStorageOptimizer optimizer = optimizer(telegramClient);
        Logger logger = (Logger) LoggerFactory.getLogger(TelegramStorageOptimizer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);

        try {
            assertThatCode(optimizer::run).doesNotThrowAnyException();
            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Telegram storage optimization failed");
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            appender.stop();
        }
    }

    @Test
    void runsImmediatelyAndThenSixHoursAfterEachCompletion() throws Exception {
        Method run = TelegramStorageOptimizer.class.getMethod("run");

        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("PT6H");
        assertThat(scheduled.initialDelayString()).isEmpty();
    }

    private TelegramStorageOptimizer optimizer(TelegramClient telegramClient) {
        TelegramStorageOptimizer optimizer = new TelegramStorageOptimizer();
        ReflectionTestUtils.setField(optimizer, "telegramClient", telegramClient);
        return optimizer;
    }

}
