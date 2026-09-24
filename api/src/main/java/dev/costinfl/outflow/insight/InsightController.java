package dev.costinfl.outflow.insight;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/insights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "insights")
public class InsightController {

    private final InsightService insights;

    public InsightController(InsightService insights) {
        this.insights = insights;
    }

    /** Category detail: the month, a 12-month trend with its average, and the month's merchants. */
    @GetMapping("/categories/{id}")
    public CategoryDetail category(
            @org.springframework.web.bind.annotation.PathVariable long id,
            @Parameter(description = "YYYY-MM") @RequestParam String month,
            @RequestParam(defaultValue = "RON") String currency) {
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        return insights.category(id, ym, currency)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No category " + id));
    }

    /** The home screen's answer for one month. */
    @GetMapping("/month")
    public MonthSummary month(
            @Parameter(description = "YYYY-MM; defaults to the latest month with data") @RequestParam(required = false) String month,
            @Parameter(description = "ISO currency; v1 reports one currency at a time") @RequestParam(defaultValue = "RON") String currency) {
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        YearMonth ym;
        if (month == null) {
            var available = insights.availableMonths(currency);
            ym = available.isEmpty() ? YearMonth.now() : available.getLast();
        } else {
            try {
                ym = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
            }
        }
        return insights.month(ym, currency);
    }
}
