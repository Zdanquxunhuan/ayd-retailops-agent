package com.yu.retailops.runtime;

import com.yu.retailops.domain.Investigation.*;

/**
 * One model call jointly updates hypotheses and proposes the next bounded query.
 */
public interface InvestigationPlanner {
    Reply investigate(Context context) throws Exception;
}
