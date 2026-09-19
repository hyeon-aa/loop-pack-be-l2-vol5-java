package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointBalanceInfo;
import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller {

    private final PointFacade pointFacade;

    @GetMapping
    public ApiResponse<PointResponse> getBalance(@RequestHeader("X-USER-ID") Long userId) {
        return ApiResponse.success(PointResponse.from(pointFacade.getBalance(userId)));
    }

    @PostMapping("/charge")
    public ApiResponse<PointResponse> charge(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody ChargeRequest request
    ) {
        return ApiResponse.success(PointResponse.from(pointFacade.charge(userId, request.amount())));
    }

    public record ChargeRequest(long amount) {}

    public record PointResponse(long balance) {
        public static PointResponse from(PointBalanceInfo info) {
            return new PointResponse(info.balance());
        }
    }
}
