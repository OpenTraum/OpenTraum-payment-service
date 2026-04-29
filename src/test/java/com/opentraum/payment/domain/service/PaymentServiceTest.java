package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.client.PortOneClient;
import com.opentraum.payment.domain.dto.WebhookRequest;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.outbox.service.OutboxService;
import com.opentraum.payment.domain.repository.PaymentQueryRepository;
import com.opentraum.payment.domain.repository.PaymentRepository;
import com.opentraum.payment.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentQueryRepository paymentQueryRepository = mock(PaymentQueryRepository.class);
    private final PortOneClient portOneClient = mock(PortOneClient.class);
    private final PaymentTimerService timerService = mock(PaymentTimerService.class);
    private final WebClient reservationWebClient = mock(WebClient.class);
    private final WebClient eventWebClient = mock(WebClient.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);

    private PaymentService service;

    @BeforeEach
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new PaymentService(
                paymentRepository,
                paymentQueryRepository,
                portOneClient,
                timerService,
                reservationWebClient,
                eventWebClient,
                outboxService,
                transactionalOperator,
                0L);
    }

    @Test
    void completePaymentDoesNotPublishFailureWhenVerificationCallFails() {
        Payment payment = pendingPayment();
        RuntimeException timeout = new RuntimeException("portone-timeout");
        when(paymentRepository.findByMerchantUid("merchant-1")).thenReturn(Mono.just(payment));
        when(portOneClient.verifyPayment("merchant-1", 1000)).thenReturn(Mono.error(timeout));

        StepVerifier.create(service.completePayment(webhook("merchant-1")))
                .expectErrorMatches(error -> error == timeout)
                .verify();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING.name());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(outboxService, never()).publish(anyLong(), anyString(), eq("PaymentFailed"), anyString(), anyMap());
        verify(timerService, never()).cancelPaymentTimer(anyLong());
    }

    @Test
    void completePaymentPublishesFailureOnlyForVerifiedPaymentMismatch() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByMerchantUid("merchant-1")).thenReturn(Mono.just(payment));
        when(portOneClient.verifyPayment("merchant-1", 1000)).thenReturn(Mono.just(
                PortOneClient.PaymentVerificationResult.builder()
                        .paymentId("merchant-1")
                        .amount(900)
                        .status(PaymentStatus.COMPLETED)
                        .build()));
        when(paymentRepository.save(payment)).thenReturn(Mono.just(payment));
        when(outboxService.publish(anyLong(), anyString(), eq("PaymentFailed"), anyString(), anyMap()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.completePayment(webhook("merchant-1")))
                .expectError(BusinessException.class)
                .verify();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED.name());
        verify(paymentRepository).save(payment);
        verify(outboxService).publish(eq(10L), eq("payment"), eq("PaymentFailed"), anyString(), anyMap());
        verify(timerService, never()).cancelPaymentTimer(anyLong());
    }

    private static Payment pendingPayment() {
        return Payment.builder()
                .id(1L)
                .reservationId(10L)
                .merchantUid("merchant-1")
                .amount(1000)
                .status(PaymentStatus.PENDING.name())
                .build();
    }

    private static WebhookRequest webhook(String merchantUid) {
        WebhookRequest request = new WebhookRequest();
        request.setMerchantUid(merchantUid);
        request.setImpUid("imp-1");
        return request;
    }
}
