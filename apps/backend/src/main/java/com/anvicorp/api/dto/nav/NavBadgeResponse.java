package com.anvicorp.api.dto.nav;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Optional badge attached to a nav item.
 *
 * <ul>
 *   <li>{@code type=count, value=N} — surfaces a meaningful count
 *       (interviews awaiting, undecided offers, pending compliance tasks).</li>
 *   <li>{@code type=new} — the item just unlocked and the user hasn't opened
 *       it yet; clears after the user opens the route or calls
 *       {@code POST /api/v1/candidate/nav/seen}.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NavBadgeResponse {
    /** "count" or "new". */
    private String type;
    /** Only populated for type=count. */
    private Integer value;
}
