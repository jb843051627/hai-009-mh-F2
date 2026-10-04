package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TMhTriaRuleMapper;
import com.fc.v2.model.auto.TMhTriaRule;
import com.fc.v2.model.custom.health.HealthEvalResult;
import com.fc.v2.service.impl.TMhTriaRuleServiceImpl;

/**
 * 分诊判定线口径测试：每条用例对应规格里的一句话，注释标明出处。
 *  mapper 用 Mockito 挡掉，SQL 侧粗筛由服务层内存精筛兜底复核，故
 *  桩数据直接给全量行，考验的是服务层自己的口径。
 */
public class TMhTriaRuleServiceTest {

    private TMhTriaRuleServiceImpl service;
    private List<TMhTriaRule> rows;

    @BeforeEach
    public void setUp() {
        rows = new ArrayList<TMhTriaRule>();
        TMhTriaRuleMapper mapper = mock(TMhTriaRuleMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> rows);
        when(mapper.selectById(any())).thenAnswer(inv -> {
            Long id = (Long) inv.getArgument(0);
            for (TMhTriaRule r : rows) {
                if (r.getId() != null && r.getId().equals(id)) {
                    return r;
                }
            }
            return null;
        });
        service = new TMhTriaRuleServiceImpl();
        ReflectionTestUtils.setField(service, "mhTriaRuleMapper", mapper);
    }

    private static Date at(String ymdHms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(ymdHms);
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static TMhTriaRule rule(long id, String code, String th1, String th2, String th3,
                                    String start, String end, int priority, int status) {
        TMhTriaRule r = new TMhTriaRule();
        r.setId(id);
        r.setRuleCode(code);
        r.setRuleName(code + "版判定线");
        r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        r.setEffStart(start == null ? null : at(start));
        r.setEffEnd(end == null ? null : at(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    // ---- 交棒：旧线管到交棒前一刻，新线从交棒那一刻起算（含起不含止） ----

    @Test
    public void handoverMoment_oldStopsJustBefore_newStartsExactly() {
        rows.add(rule(1L, "TRIA-OLD", "10", "20", "30",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 0, 0));
        rows.add(rule(2L, "TRIA-NEW", "50", "60", "70",
                "2026-01-01 00:00:00", null, 0, 0));

        // 交棒前一刻：旧线说话（15 落在旧线 10~20 → 2 级）
        HealthEvalResult before = service.evaluateTop(new BigDecimal("15"),
                at("2025-12-31 23:59:59"), null, null);
        assertTrue(before.isAvailable());
        assertEquals(2, before.getLevel());
        assertEquals("TRIA-OLD", before.getRuleCode());

        // 交棒那一刻：新线说话（15 落在新线 50 以内 → 1 级）；
        // 若交棒日按"含"算，此刻新旧并存，按代号排后一条会错挑 TRIA-OLD
        HealthEvalResult atT = service.evaluateTop(new BigDecimal("15"),
                at("2026-01-01 00:00:00"), null, null);
        assertTrue(atT.isAvailable());
        assertEquals(1, atT.getLevel());
        assertEquals("TRIA-NEW", atT.getRuleCode());

        // 急诊室的屏和护理部复核的屏数得一致：任何时刻可用线恰好一条，角标=列表
        assertEquals(1, service.countAvailable(at("2025-12-31 23:59:59")));
        assertEquals(1, service.listAvailable(at("2025-12-31 23:59:59")).size());
        assertEquals(1, service.countAvailable(at("2026-01-01 00:00:00")));
        assertEquals(1, service.listAvailable(at("2026-01-01 00:00:00")).size());
    }

    // ---- 取优：顺位大的说了算，顺位分不出就按代号排后一条，年年一个讲法 ----

    @Test
    public void concurrentLines_higherPriorityWins() {
        // 低顺位的线阈值宽（80 分算 1 级），高顺位的线阈值窄（80 分算 4 级）
        rows.add(rule(1L, "TRIA-WIDE", "90", "95", "99",
                "2026-01-01 00:00:00", null, 1, 0));
        rows.add(rule(2L, "TRIA-NARROW", "5", "10", "20",
                "2026-01-01 00:00:00", null, 9, 0));
        HealthEvalResult r = service.evaluateTop(new BigDecimal("80"),
                at("2026-06-01 08:00:00"), null, null);
        assertEquals("TRIA-NARROW", r.getRuleCode());
        assertEquals(4, r.getLevel());
    }

    @Test
    public void concurrentLines_tieOnPriority_laterCodeWins_regardlessOfRowOrder() {
        TMhTriaRule a = rule(1L, "TRIA-A", "90", "95", "99",
                "2026-01-01 00:00:00", null, 5, 0);
        TMhTriaRule b = rule(2L, "TRIA-B", "5", "10", "20",
                "2026-01-01 00:00:00", null, 5, 0);

        rows.addAll(Arrays.asList(a, b));
        HealthEvalResult r1 = service.evaluateTop(new BigDecimal("80"),
                at("2026-06-01 08:00:00"), null, null);
        assertEquals("TRIA-B", r1.getRuleCode());   // 代号排后一条说了算
        assertEquals("TRIA-B", service.listAvailable(at("2026-06-01 08:00:00")).get(0).getRuleCode());

        // 库里行序反过来，挑的还是那一条——不能今年挑前条、明年挑后条
        rows.clear();
        rows.addAll(Arrays.asList(b, a));
        HealthEvalResult r2 = service.evaluateTop(new BigDecimal("80"),
                at("2026-06-01 08:00:00"), null, null);
        assertEquals("TRIA-B", r2.getRuleCode());
    }

    // ---- 分档：卡在分界数上的算上一级；空格不设档，往下一档接着判 ----

    @Test
    public void scoreExactlyOnBoundary_countsAsUpperLevel() {
        rows.add(rule(1L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", null, 0, 0));
        Date at = at("2026-06-01 08:00:00");
        assertEquals(1, service.evaluateTop(new BigDecimal("10"), at));
        assertEquals(2, service.evaluateTop(new BigDecimal("20"), at));
        assertEquals(3, service.evaluateTop(new BigDecimal("30"), at));
        assertEquals(4, service.evaluateTop(new BigDecimal("30.01"), at));
        assertEquals(1, service.evaluateTop(new BigDecimal("0"), at));
        assertEquals(4, service.evaluateTop(new BigDecimal("100"), at));
    }

    @Test
    public void emptyThresholdCell_tierSkipped_fallsToNextTier() {
        // 危重档界值空着：不设那一档，5 分直接落紧急档
        rows.add(rule(1L, "TRIA-A", null, "20", "30",
                "2026-01-01 00:00:00", null, 0, 0));
        Date at = at("2026-06-01 08:00:00");
        assertEquals(2, service.evaluateTop(new BigDecimal("5"), at));
        assertEquals(3, service.evaluateTop(new BigDecimal("25"), at));
        assertEquals(4, service.evaluateTop(new BigDecimal("35"), at));

        // 紧急档界值空着：15 分越过空档落普通档
        rows.clear();
        rows.add(rule(2L, "TRIA-B", "10", null, "30",
                "2026-01-01 00:00:00", null, 0, 0));
        assertEquals(3, service.evaluateTop(new BigDecimal("15"), at));
    }

    // ---- 得分：为负、离谱大数或空着不评，退回护士重录 ----

    @Test
    public void invalidScore_rejected_backToNurse() {
        rows.add(rule(1L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", null, 0, 0));
        Date at = at("2026-06-01 08:00:00");
        assertEquals(0, service.evaluateTop(new BigDecimal("-1"), at));
        assertEquals(0, service.evaluateTop(new BigDecimal("101"), at));
        assertEquals(0, service.evaluateTop(null, at));

        HealthEvalResult r = service.evaluateTop(new BigDecimal("999"),
                at, "急诊", "预检分诊");
        assertFalse(r.isAvailable());
        assertEquals(0, r.getLevel());
        assertTrue(r.getMessage().contains("退回护士重录"));
        // 被挡的回执不得带线代号，免得误写上单
        assertNull(r.getRuleCode());
    }

    // ---- 查不到可用的线：单上明写"无可用标准"，不拿前后几天的凑合 ----

    @Test
    public void noLineAtThatMoment_saysNoAvailableStandard() {
        rows.add(rule(1L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", "2026-02-01 00:00:00", 0, 0));
        Date gap = at("2026-03-01 08:00:00");
        assertEquals(0, service.evaluateTop(new BigDecimal("15"), gap));
        HealthEvalResult r = service.evaluateTop(new BigDecimal("15"), gap, null, null);
        assertFalse(r.isAvailable());
        assertEquals("无可用标准", r.getMessage());
    }

    // ---- 复查：只认单上代号，调那条线按当时口径重判；让位的线只供翻旧账 ----

    @Test
    public void review_usesVersionEffectiveOnTriageDay_evenIfYielded() {
        // 同一代号两版：旧版已让位（status=1），新版现行
        rows.add(rule(1L, "TRIA-X", "10", "20", "30",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 0, 1));
        rows.add(rule(2L, "TRIA-X", "50", "60", "70",
                "2026-01-01 00:00:00", null, 0, 0));

        // 去年的单子还按旧线核：15 分按旧版 10/20/30 → 2 级
        HealthEvalResult last = service.evaluate("TRIA-X", new BigDecimal("15"),
                at("2025-06-15 10:00:00"), null, null);
        assertTrue(last.isAvailable());
        assertEquals(2, last.getLevel());
        assertEquals("TRIA-X", last.getRuleCode());
        // 单上要留那条线的起用日
        assertEquals(at("2025-01-01 00:00:00"), last.getEffStart());

        // 同一得分今年评，按新版 50/60/70 → 1 级：不许拿今天的标准改昨天的单子，
        // 也不许拿昨天的标准评今天的单子
        HealthEvalResult now = service.evaluate("TRIA-X", new BigDecimal("15"),
                at("2026-06-15 10:00:00"), null, null);
        assertEquals(1, now.getLevel());
        assertEquals(at("2026-01-01 00:00:00"), now.getEffStart());
    }

    @Test
    public void review_neverBorrowsFromAdjacentDays() {
        rows.add(rule(1L, "TRIA-X", "10", "20", "30",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 0, 1));
        // 该代号在 2024 年没有生效版本：空手而归，不拿 2025 年的凑合
        HealthEvalResult r = service.evaluate("TRIA-X", new BigDecimal("15"),
                at("2024-06-15 10:00:00"), null, null);
        assertFalse(r.isAvailable());
        assertEquals("无可用标准", r.getMessage());
        assertEquals(0, service.evaluate("TRIA-X", new BigDecimal("15"),
                at("2024-06-15 10:00:00")));
    }

    @Test
    public void yieldedLine_staysOutOfNewDetermination() {
        // 让位的线只供翻旧账：新定级那一刻只有它在窗口内，也算无可用标准
        rows.add(rule(1L, "TRIA-X", "10", "20", "30",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 0, 1));
        Date at = at("2025-06-15 10:00:00");
        assertEquals(0, service.evaluateTop(new BigDecimal("15"), at));
        assertEquals("无可用标准",
                service.evaluateTop(new BigDecimal("15"), at, null, null).getMessage());
        assertTrue(service.listAvailable(at).isEmpty());
        assertEquals(0, service.countAvailable(at));
    }

    // ---- 回执：原始得分、级别、用哪条线、那条线的起用日随后台回值落单 ----

    @Test
    public void receipt_carriesLineIdentityForTheForm() {
        rows.add(rule(7L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", "2027-01-01 00:00:00", 3, 0));
        Date at = at("2026-06-01 08:00:00");
        HealthEvalResult r = service.evaluateTop(new BigDecimal("15"), at, "急诊", "预检分诊");
        assertTrue(r.isAvailable());
        assertEquals(2, r.getLevel());
        assertEquals(new BigDecimal("15"), r.getInput());
        assertEquals(at, r.getAt());
        assertEquals("TRIA-A", r.getRuleCode());
        assertEquals("TRIA-A版判定线", r.getRuleName());
        assertEquals(at("2026-01-01 00:00:00"), r.getEffStart());
        assertEquals(at("2027-01-01 00:00:00"), r.getEffEnd());
        assertEquals(Integer.valueOf(3), r.getPriority());
        assertEquals("急诊", r.getSiteType());
        assertEquals("预检分诊", r.getSubSystem());
    }

    // ---- 旁路核对：usable 与列表同一口径 ----

    @Test
    public void usable_matchesListSemantics() {
        rows.add(rule(1L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", "2026-02-01 00:00:00", 0, 0));
        assertTrue(service.usable(1L, at("2026-01-15 00:00:00")));
        // 交棒那一刻即不可用（含起不含止）
        assertFalse(service.usable(1L, at("2026-02-01 00:00:00")));
        // 起用之前不可用
        assertFalse(service.usable(1L, at("2025-12-31 23:59:59")));

        // 已让位、已删除的线任何路径都不认
        rows.clear();
        rows.add(rule(2L, "TRIA-B", "10", "20", "30",
                "2026-01-01 00:00:00", null, 0, 1));
        assertFalse(service.usable(2L, at("2026-01-15 00:00:00")));
        TMhTriaRule deleted = rule(3L, "TRIA-C", "10", "20", "30",
                "2026-01-01 00:00:00", null, 0, 0);
        deleted.setDelFlag(1);
        rows.add(deleted);
        assertFalse(service.usable(3L, at("2026-01-15 00:00:00")));
    }

    // ---- 契约：老取线方法签名不动，新能力在同名单下挂一层 ----

    @Test
    public void legacySignaturesUntouched_newLayerOverloaded() throws Exception {
        assertEquals(int.class, ITMhTriaRuleService.class
                .getMethod("evaluate", String.class, BigDecimal.class, Date.class).getReturnType());
        assertEquals(int.class, ITMhTriaRuleService.class
                .getMethod("evaluateTop", BigDecimal.class, Date.class).getReturnType());
        assertEquals(List.class, ITMhTriaRuleService.class
                .getMethod("listAvailable", Date.class).getReturnType());
        assertEquals(boolean.class, ITMhTriaRuleService.class
                .getMethod("usable", Long.class, Date.class).getReturnType());
        assertEquals(int.class, ITMhTriaRuleService.class
                .getMethod("countAvailable", Date.class).getReturnType());
        // 同名单下挂的回执层
        assertEquals(HealthEvalResult.class, ITMhTriaRuleService.class
                .getMethod("evaluate", String.class, BigDecimal.class, Date.class,
                        String.class, String.class).getReturnType());
        assertEquals(HealthEvalResult.class, ITMhTriaRuleService.class
                .getMethod("evaluateTop", BigDecimal.class, Date.class,
                        String.class, String.class).getReturnType());
    }

    // ---- 老签名与新层同一个口径：返回值只是回执的级别位 ----

    @Test
    public void legacyMethods_delegateToReceiptLayer() {
        rows.add(rule(1L, "TRIA-A", "10", "20", "30",
                "2026-01-01 00:00:00", null, 0, 0));
        Date at = at("2026-06-01 08:00:00");
        assertEquals(service.evaluateTop(new BigDecimal("15"), at, null, null).getLevel(),
                service.evaluateTop(new BigDecimal("15"), at));
        assertEquals(service.evaluate("TRIA-A", new BigDecimal("15"), at, null, null).getLevel(),
                service.evaluate("TRIA-A", new BigDecimal("15"), at));
        assertNotNull(service.evaluateTop(new BigDecimal("15"), at, null, null));
    }
}
