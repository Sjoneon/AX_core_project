package com.project.backend.data;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.mcp.tool.ToolDataCatalog;

/**
 * Cross-checks parts.json against the original v3 cost CSV when that untracked file is present locally
 * (수리비_정규화_과정/ next to backend/). The original is only read. In CI without the file the test is skipped.
 */
class CostTableSourceTests {
    static final Path CSV=Path.of("..","수리비_정규화_과정","한국기준_예상수리비_표_v3.csv");
    static final Map<String,String> CATEGORIES=Map.of("세탁기","WASHER","건조기","DRYER","냉장고","REFRIGERATOR","식기세척기","DISHWASHER");

    @Test void partsMatchEveryNonExcludedRowIncludingSurcharges() throws Exception {
        assumeTrue(Files.exists(CSV),"원본 수리비 CSV가 없어 대조를 건너뜁니다.");
        byte[] raw=Files.readAllBytes(CSV);
        var manifest=DataManifest.classpath();
        assertThat(DataManifest.sha256(raw)).isEqualTo(manifest.section("costSource").get("sha256"));
        var parts=new ToolDataCatalog(manifest).parts;
        var lines=new String(raw,StandardCharsets.UTF_8).replace("﻿","").lines().toList();
        assertThat(lines.getFirst()).contains("야간·휴일 가산","6~8월 가산(평일/야간)");
        int compared=0;
        for(var line:lines.subList(1,lines.size())){
            var c=line.split(",",-1);
            if(c[16].startsWith("제외")){assertThat(parts).noneMatch(p->c[1].equals(p.get("id")));continue;}
            var part=parts.stream().filter(p->c[1].equals(p.get("id"))).findFirst().orElseThrow();
            var summer=c[15].split("/");
            assertThat(part).as(c[1]).containsEntry("category",CATEGORIES.get(c[0])).containsEntry("name",c[2])
                    .containsEntry("empiricalMedian",Integer.parseInt(c[11])).containsEntry("empiricalMin",Integer.parseInt(c[12]))
                    .containsEntry("empiricalMax",Integer.parseInt(c[13])).containsEntry("baseVisit",Integer.parseInt(c[6]))
                    .containsEntry("nightHolidaySurcharge",Integer.parseInt(c[14])).containsEntry("summerWeekdaySurcharge",Integer.parseInt(summer[0]))
                    .containsEntry("summerNightHolidaySurcharge",Integer.parseInt(summer[1])).containsEntry("confidenceGrade",c[16]);
            compared++;
        }
        requireNonNull(assertThat(compared).isEqualTo(parts.size())).isEqualTo(32);
    }
}
