package com.memsys.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WeeklyReviewJob {

    private final WeeklyReviewService weeklyReviewService;

    public WeeklyReviewJob(WeeklyReviewService weeklyReviewService) {
        this.weeklyReviewService = weeklyReviewService;
    }

    @Scheduled(cron = "${scheduling.weekly-review-cron:0 0 9 ? * MON}")
    public void generateAndPushWeeklyReview() {
        if (!weeklyReviewService.isWeeklyReviewEnabled()) {
            return;
        }
        try {
            WeeklyReviewService.WeeklyPushResult result = weeklyReviewService.generateAndPushAllPersonalReviews();
            if (result.generated() > 0 || result.pushed() > 0 || result.failedPush() > 0) {
                log.info("Weekly review job done. generated={}, pushed={}, failedPush={}",
                        result.generated(), result.pushed(), result.failedPush());
            }
        } catch (Exception e) {
            log.warn("Weekly review job failed", e);
        }
    }
}
