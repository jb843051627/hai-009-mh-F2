package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMhTriaRuleMapper;
import com.fc.v2.model.auto.TMhTriaRule;
import com.fc.v2.model.custom.health.HealthEvalResult;
import com.fc.v2.service.ITMhTriaRuleService;

/**
 * 急诊分诊判定线 Service业务层处理（rule-eval 形状）
 *
 * 全院定级口径（改动前先读这里，年年一个讲法）：
 * ① 定级只看"发生分诊那一天"生效的线，不看今天：起用日含、交棒日不含
 *    （旧线管到交棒前一刻，新线从交棒那一刻起算）；查不到就明写"无可用标准"，
 *    不拿前后几天的线凑合；
 * ② 同一时段并着几条线：顺位大的说了算；顺位并列按代号排后一条；再并列按起用日
 *    较晚的一条；最后按主键——任何时刻全院只有一条线说话，两块屏数得一致；
 * ③ 已让位的线不参与新的定级（listAvailable/evaluateTop/countAvailable/usable），
 *    但复查按单上代号调得出（evaluate 按代号那条链不卡 status），只供翻旧账；
 * ④ 三个分界数把得分切成四段，正好卡在分界数上的算上一级；早期入册的线分界数
 *    空着就不设那一档，往下一档接着判，不算故障；
 * ⑤ 得分为负、离谱大数或空着：不评，退回护士重录。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMhTriaRuleServiceImpl implements ITMhTriaRuleService {

    /** 得分量程上限：超过即"离谱大数"，退回重录 */
    private static final BigDecimal INPUT_MAX = new BigDecimal("100");

    /** 查不到可用的线时单上明写 */
    private static final String MSG_NO_RULE = "无可用标准";

    /** 得分不评时的提示 */
    private static final String MSG_BAD_INPUT = "得分无效（空着/为负/超出量程0~100），退回护士重录";

    /**
     * 取优口径：顺位大的先；并列按代号排后一条；再并列按起用日较晚一条；
     * 最后按主键升序兜底——全序固定，今年明年挑的都是同一条。
     */
    private static final Comparator<TMhTriaRule> PRIORITY_THEN_CODE_DESC = new Comparator<TMhTriaRule>() {
        @Override
        public int compare(TMhTriaRule a, TMhTriaRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            // 同代号同顺位的脏数据：起用日较晚的一版先，保证确定性
            String sa = a.getEffStart() == null ? "" : ts(a.getEffStart());
            String sb = b.getEffStart() == null ? "" : ts(b.getEffStart());
            int byStart = sb.compareTo(sa);
            if (byStart != 0) {
                return byStart;
            }
            long ia = a.getId() == null ? Long.MAX_VALUE : a.getId();
            long ib = b.getId() == null ? Long.MAX_VALUE : b.getId();
            return Long.compare(ia, ib);
        }
    };

    @javax.annotation.Resource
    private TMhTriaRuleMapper mhTriaRuleMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /**
     * 生效窗口唯一判定入口：起用日含、交棒日不含（旧线管到交棒前一刻，
     * 新线从交棒那一刻起算）；起用日空 = 一入册即生效；交棒日空或脏值
     * "0000-00-00" = 长期有效。列表/角标/旁路/复查全走这里，口径不走岔。
     */
    private static boolean inWindow(TMhTriaRule r, String now) {
        String s = r.getEffStart() == null ? null : ts(r.getEffStart());
        String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
        if (e != null && e.startsWith("0000-00-00")) {
            e = null;   // 兼容存量：早期导入的脏值为"长期有效"
        }
        boolean started = (s == null) || s.compareTo(now) <= 0;
        boolean notEnded = (e == null) || e.compareTo(now) > 0;
        return started && notEnded;
    }

    /** 得分为负、离谱大数或空着：不评，退回护士重录 */
    private static boolean invalidInput(BigDecimal input) {
        return input == null
                || input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(INPUT_MAX) > 0;
    }

    @Override
    public TMhTriaRule selectTMhTriaRuleById(Long id) {
        return this.mhTriaRuleMapper.selectById(id);
    }

    @Override
    public List<TMhTriaRule> listAvailable(Date at) {
        List<TMhTriaRule> out = new java.util.ArrayList<TMhTriaRule>();
        if (at == null) {
            return out;
        }
        // ① SQL 侧粗筛（del_flag/status 可走索引）
        QueryWrapper<TMhTriaRule> q = new QueryWrapper<TMhTriaRule>();
        q.eq("del_flag", 0).eq("status", 0);
        List<TMhTriaRule> rows = this.mhTriaRuleMapper.selectList(q);
        if (rows != null) {
            // ② 内存精筛：与 findRule/usable/countAvailable 同一口径（含起不含止），
            //    已让位的线不进可用集——只供翻旧账，不参与新的定级
            String now = ts(at);
            for (TMhTriaRule r : rows) {
                if (r.getDelFlag() != null && r.getDelFlag().intValue() == 0
                        && r.getStatus() != null && r.getStatus().intValue() == 0
                        && inWindow(r, now)) {
                    out.add(r);
                }
            }
        }
        // ③ 取优口径固定：顺位→代号→起用日→主键，看板与定级拿的是同一份顺序
        out.sort(PRIORITY_THEN_CODE_DESC);
        // 可用集只服务看板与取优，超过 200 条时截断，防止大规则集拖慢页面
        if (out.size() > 200) {
            out = new java.util.ArrayList<TMhTriaRule>(out.subList(0, 200));
        }
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        HealthEvalResult r = evaluate(ruleCode, input, at, null, null);
        return r.isAvailable() ? r.getLevel() : 0;
    }

    @Override
    public HealthEvalResult evaluate(String ruleCode, BigDecimal input, Date at,
                                     String siteType, String subSystem) {
        if (ruleCode == null || ruleCode.trim().isEmpty()) {
            return reject(input, at, siteType, subSystem, "判定线代号为空，退回护士重录");
        }
        if (at == null) {
            return reject(input, null, siteType, subSystem, "分诊时刻为空，退回护士重录");
        }
        if (invalidInput(input)) {
            return reject(input, at, siteType, subSystem, MSG_BAD_INPUT);
        }
        TMhTriaRule rule = findRule(ruleCode, at);
        if (rule == null) {
            return reject(input, at, siteType, subSystem, MSG_NO_RULE);
        }
        return hit(rule, input, at, siteType, subSystem);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        HealthEvalResult r = evaluateTop(input, at, null, null);
        return r.isAvailable() ? r.getLevel() : 0;
    }

    @Override
    public HealthEvalResult evaluateTop(BigDecimal input, Date at,
                                        String siteType, String subSystem) {
        if (at == null) {
            return reject(input, null, siteType, subSystem, "分诊时刻为空，退回护士重录");
        }
        if (invalidInput(input)) {
            return reject(input, at, siteType, subSystem, MSG_BAD_INPUT);
        }
        List<TMhTriaRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return reject(input, at, siteType, subSystem, MSG_NO_RULE);
        }
        // 可用集已按取优口径排定，头一条就是那一刻全院唯一说话的线
        return hit(avail.get(0), input, at, siteType, subSystem);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TMhTriaRule r = this.mhTriaRuleMapper.selectById(id);
        if (r == null || r.getStatus() == null || r.getStatus().intValue() != 0) {
            return false;
        }
        // 逻辑删除的线任何路径都不认
        if (r.getDelFlag() == null || r.getDelFlag().intValue() != 0) {
            return false;
        }
        // 旁路复用也必须与定位/列表同口径，否则"列表里有、导出里没有"
        if (!inWindow(r, ts(at))) {
            return false;
        }
        // 阈值全缺的规则视为不可用（求值会误判到最高档）
        if (r.getTh1Max() == null && r.getTh2Max() == null && r.getTh3Max() == null) {
            return false;
        }
        // 量程异常（上限越界）的规则不参与，避免把脏配置算成高档
        if (r.getTh3Max() != null && r.getTh3Max().compareTo(INPUT_MAX) > 0) {
            return false;
        }
        // 阈值解析走统一入口（缺位/精度口径与求值链一致）
        if (resolveLimit(r, 1) == null && resolveLimit(r, 2) == null
                && resolveLimit(r, 3) == null) {
            return false;
        }
        return true;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        QueryWrapper<TMhTriaRule> q = new QueryWrapper<TMhTriaRule>();
        q.eq("del_flag", 0).eq("status", 0);
        List<TMhTriaRule> rows = this.mhTriaRuleMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 角标口径必须与列表逐条一致（口径走岔会出现"角标 3、列表 2"的线上投诉）
        String now = ts(at);
        int n = 0;
        for (TMhTriaRule r : rows) {
            if (r.getDelFlag() != null && r.getDelFlag().intValue() == 0
                    && r.getStatus() != null && r.getStatus().intValue() == 0
                    && inWindow(r, now)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 复查取线：只认单上记的代号，调出"分诊那一刻"生效的那一版，按当时口径重判。
     * 已让位的线照样调得出（让位只挡新的定级，不挡翻旧账）；
     * 那一刻没有生效版本就空手而归，不拿前后几天的凑合。
     */
    private TMhTriaRule findRule(String ruleCode, Date at) {
        // ① SQL 侧粗筛（可走 rule_code 索引）
        QueryWrapper<TMhTriaRule> q = new QueryWrapper<TMhTriaRule>();
        q.eq("del_flag", 0).eq("rule_code", ruleCode);
        List<TMhTriaRule> rows = this.mhTriaRuleMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // ② 内存精筛：代号逐条核对 + 含起不含止；不卡 status（让位的线只供这里翻旧账）
        List<TMhTriaRule> hit = new java.util.ArrayList<TMhTriaRule>();
        String now = ts(at);
        for (TMhTriaRule r : rows) {
            if (r.getDelFlag() != null && r.getDelFlag().intValue() == 0
                    && ruleCode.equals(r.getRuleCode())
                    && inWindow(r, now)) {
                hit.add(r);
            }
        }
        if (hit.isEmpty()) {
            return null;
        }
        // ③ 同代号并着几版时，与新定级取优同一个讲法
        hit.sort(PRIORITY_THEN_CODE_DESC);
        return hit.get(0);
    }

    /** 阈值解析：档案为主、明细覆盖；两者皆缺返回 null（调用方按不可用处理），精度统一 2 位 */
    private BigDecimal resolveLimit(TMhTriaRule r, int tier) {
        if (r == null) {
            return null;
        }
        BigDecimal v = null;
        switch (tier) {
            case 1:
                v = r.getTh1Max();
                break;
            case 2:
                v = r.getTh2Max();
                break;
            case 3:
                v = r.getTh3Max();
                break;
            default:
                v = null;
                break;
        }
        if (v == null) {
            // 明细未维护时回退到档案级默认阈值（兼容存量规则只配了档案值的情况）
            v = defaultThreshold(r, tier);
        }
        if (v == null) {
            return null;
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 档案级默认阈值（明细未维护时的回退来源） */
    private BigDecimal defaultThreshold(TMhTriaRule r, int tier) {
        if (tier == 1) {
            return r.getTh1Max();
        }
        if (tier == 2) {
            return r.getTh2Max();
        }
        return r.getTh3Max();
    }

    /**
     * 分档：三个分界数把得分切成四段，正好卡在分界数上的算上一级；
     * 分界数空着就不设那一档，往下一档接着判，不算故障。
     */
    private int levelOf(TMhTriaRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }

    /** 不评/套不上：available=false、level=0，调用方不得把它当成某个档位 */
    private HealthEvalResult reject(BigDecimal input, Date at,
                                    String siteType, String subSystem, String message) {
        HealthEvalResult r = new HealthEvalResult();
        r.setAvailable(false);
        r.setLevel(0);
        r.setInput(input);
        r.setAt(at);
        r.setSiteType(siteType);
        r.setSubSystem(subSystem);
        r.setMessage(message);
        return r;
    }

    /** 命中：级别、用哪条线、那条线的起用日一并回值，单子照后台回值写死 */
    private HealthEvalResult hit(TMhTriaRule rule, BigDecimal input, Date at,
                                 String siteType, String subSystem) {
        HealthEvalResult r = new HealthEvalResult();
        r.setAvailable(true);
        r.setLevel(levelOf(rule, input));
        r.setInput(input);
        r.setAt(at);
        r.setSiteType(siteType);
        r.setSubSystem(subSystem);
        r.setRuleCode(rule.getRuleCode());
        r.setRuleName(rule.getRuleName());
        r.setEffStart(rule.getEffStart());
        r.setEffEnd(rule.getEffEnd());
        r.setPriority(rule.getPriority());
        return r;
    }
}
