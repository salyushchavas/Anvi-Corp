package com.anvicorp.api.service;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class OfferLetterTemplate {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter EXPIRES_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH);

    private final BrandConfig brand;

    public String generate(Candidate candidate,
                           JobPosting posting,
                           StaffingEntity entity,
                           Offer offer) {

        User user = candidate != null ? candidate.getUser() : null;
        String fullName = user != null && user.getFullName() != null ? user.getFullName().trim() : "";
        String firstName;
        String lastName;
        int sp = fullName.indexOf(' ');
        if (sp > 0) {
            firstName = fullName.substring(0, sp);
            lastName = fullName.substring(sp + 1).trim();
        } else {
            firstName = fullName.isEmpty() ? "Candidate" : fullName;
            lastName = "";
        }

        String entityName = entity != null && entity.getName() != null ? entity.getName() : "the company";
        String positionTitle = posting != null && posting.getTitle() != null
                ? posting.getTitle()
                : "the offered position";
        String location = posting != null && posting.getLocation() != null ? posting.getLocation() : "—";

        String startStr = formatDate(offer.getStartDate());
        String endLine = offer.getExpectedEndDate() != null
                ? "  • End Date: " + formatDate(offer.getExpectedEndDate()) + "\n"
                : "";

        String compStr = String.format(Locale.ENGLISH, "%s %.2f %s",
                offer.getCompensationCurrency(),
                offer.getCompensationAmount(),
                offer.getCompensationFrequency().name().toLowerCase(Locale.ENGLISH));

        String expiresStr = offer.getExpiresAt() != null
                ? EXPIRES_FMT.format(ZonedDateTime.ofInstant(offer.getExpiresAt(), ZoneId.systemDefault()))
                : "—";

        String addTermsBlock = "";
        if (offer.getAdditionalTerms() != null && !offer.getAdditionalTerms().isBlank()) {
            addTermsBlock = "\nAdditional terms:\n" + offer.getAdditionalTerms() + "\n";
        }

        String salutationName = (firstName + " " + lastName).trim();
        if (salutationName.isEmpty()) salutationName = "Candidate";

        return "Dear " + salutationName + ",\n\n"
                + "We are delighted to extend you an offer to join " + entityName
                + " as a " + positionTitle + ".\n\n"
                + "We were impressed with your background and believe you'll be a great addition to our team. "
                + "Here are the details of your offer:\n\n"
                + "  • Position: " + positionTitle + "\n"
                + "  • Start Date: " + startStr + "\n"
                + endLine
                + "  • Compensation: " + compStr + "\n"
                + "  • Reporting Location: " + location + "\n\n"
                + "This offer is valid until " + expiresStr + ". "
                + "To accept or decline, please respond through your "
                + brand.getProductName() + " candidate dashboard.\n"
                + addTermsBlock
                + "\nWe're excited about the possibility of you joining us. "
                + "If you have any questions, please don't hesitate to reach out.\n\n"
                + "Welcome aboard,\n"
                + "The " + brand.getName() + " Hiring Team\n";
    }

    private static String formatDate(LocalDate d) {
        return d != null ? DATE_FMT.format(d) : "—";
    }
}
