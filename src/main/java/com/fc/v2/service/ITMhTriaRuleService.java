package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.fc.v2.model.auto.TMhTriaRule;
import com.fc.v2.model.custom.health.HealthEvalResult;

/**
 * TMhTriaRule 业务层处理接口
 *
 * 契约约定：老取线方法签名不动，要新能力就在同名单下挂一层（重载），
 * 不许改老签名的形参与返回类型。定级口径见实现类头注。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITMhTriaRuleService {

    /** selectTMhTriaRuleById */
    TMhTriaRule selectTMhTriaRuleById(Long id);

    /**
     * 那一刻生效的现行判定线（已让位的不在内），按取优口径排定：
     * 顺位大的在前，并列按代号排后一条在前。看板与定级共用这一份顺序。
     */
    List<TMhTriaRule> listAvailable(Date at);

    /**
     * 复查定级：只认单上记的线代号，调"分诊那一刻"生效的那一版按当时口径重判。
     * 已让位的线照样调得出；那一刻没有生效版本返回 0（无可用标准，不凑合）。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /**
     * 复查定级（回执层）：同名单下挂的一层。除级别外，把命中的线代号、
     * 名称、起用日、交棒日、顺位一并回值，单子照后台回值写死，页面不预填。
     * available=false 时 level 恒为 0，message 直说原因（无可用标准/退回重录）。
     */
    HealthEvalResult evaluate(String ruleCode, BigDecimal input, Date at,
                              String siteType, String subSystem);

    /**
     * 新定级：取"分诊那一刻"全院唯一说话的现行线算出级别。
     * 查不到可用的线返回 0（无可用标准，不拿前后几天的凑合）。
     */
    int evaluateTop(BigDecimal input, Date at);

    /**
     * 新定级（回执层）：同名单下挂的一层。级别、用哪条线、那条线的起用日
     * 随后台回值落到单子上；available=false 时 level 恒为 0。
     */
    HealthEvalResult evaluateTop(BigDecimal input, Date at,
                                 String siteType, String subSystem);

    /** usable */
    boolean usable(Long id, Date at);

    /** countAvailable */
    int countAvailable(Date at);

}
