// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CsvRecord(
        Long id,
        String name,
        String email,
        BigDecimal amount,
        LocalDate recordDate
) {
}
