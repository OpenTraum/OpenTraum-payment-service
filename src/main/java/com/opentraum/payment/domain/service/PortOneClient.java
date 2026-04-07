package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class PortOneClient {

    private WebClient webClient;

    @Value("${opentraum.portone.api-secret}")
    private String apiSecret;

    private static final String PORTONE_API_URL = "https://api.portone.io";

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(PORTONE_API_URL)
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
    }

    /**
     * 결제 검증 (V2)
     * paymentId = FE에서 PortOne.requestPayment()에 넘긴 paymentId (= 우리 merchantUid)
     */
    public Mono<PaymentVerificationResult> verifyPayment(String paymentId) {
        return webClient.get()
                .uri("/payments/{paymentId}", paymentId)
                .retrieve()
                .bodyToMono(Map.class)
                .<PaymentVerificationResult>map(response -> {
                    Map<String, Object> amount = (Map<String, Object>) response.get("amount");
                    return PaymentVerificationResult.builder()
                            .paymentId((String) response.get("id"))
                            .transactionId((String) response.get("transactionId"))
                            .amount(((Number) amount.get("total")).intValue())
                            .status(mapStatus((String) response.get("status")))
                            .build();
                })
                .doOnSuccess(result -> log.info("결제 검증 완료: paymentId={}, status={}",
                        paymentId, result.getStatus()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("PortOne 결제 검증 실패: paymentId={}, status={}, body={}",
                            paymentId, e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.error(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                            "PortOne 결제 검증 실패: " + e.getResponseBodyAsString()));
                })
                .onErrorResume(e -> !(e instanceof BusinessException), e -> {
                    log.error("PortOne 결제 검증 중 오류: paymentId={}, error={}", paymentId, e.getMessage());
                    return Mono.error(new BusinessException(ErrorCode.INTERNAL_ERROR,
                            "PortOne 결제 검증 오류: " + e.getMessage()));
                });
    }

    /**
     * 결제 취소/환불 (V2)
     * paymentId = 우리 merchantUid
     */
    public Mono<RefundResult> cancelPayment(String paymentId, int amount, String reason) {
        return webClient.post()
                .uri("/payments/{paymentId}/cancel", paymentId)
                .bodyValue(Map.of(
                        "amount", amount,
                        "reason", reason
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .<RefundResult>map(response -> {
                    Map<String, Object> cancellation = (Map<String, Object>) response.get("cancellation");
                    String status = cancellation != null ? (String) cancellation.get("status") : "FAILED";
                    return RefundResult.builder()
                            .success("SUCCEEDED".equals(status))
                            .message(reason)
                            .build();
                })
                .doOnSuccess(result -> log.info("환불 처리 완료: paymentId={}, success={}",
                        paymentId, result.isSuccess()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("PortOne 환불 실패: paymentId={}, status={}, body={}",
                            paymentId, e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.error(new BusinessException(ErrorCode.REFUND_FAILED,
                            "PortOne 환불 실패: " + e.getResponseBodyAsString()));
                })
                .onErrorResume(e -> !(e instanceof BusinessException), e -> {
                    log.error("PortOne 환불 중 오류: paymentId={}, error={}", paymentId, e.getMessage());
                    return Mono.error(new BusinessException(ErrorCode.REFUND_FAILED,
                            "PortOne 환불 오류: " + e.getMessage()));
                });
    }

    private PaymentStatus mapStatus(String portoneStatus) {
        return switch (portoneStatus) {
            case "PAID"      -> PaymentStatus.COMPLETED;
            case "CANCELLED" -> PaymentStatus.REFUNDED;
            case "FAILED"    -> PaymentStatus.FAILED;
            default          -> PaymentStatus.PENDING;
        };
    }

    @lombok.Builder
    @lombok.Getter
    public static class PaymentVerificationResult {
        private String paymentId;
        private String transactionId;
        private Integer amount;
        private PaymentStatus status;
    }

    @lombok.Builder
    @lombok.Getter
    public static class RefundResult {
        private boolean success;
        private String message;
    }
}
