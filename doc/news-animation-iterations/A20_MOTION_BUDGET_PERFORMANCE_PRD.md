# A20 需求：Motion Budget 与性能收敛

目标：在完成全部动画后建立性能边界，确保循环动画只在真实任务状态运行，主要动画只触发 transform/opacity，并继续尊重 reduced-motion。

原则：不增加新的视觉特效；本轮只做动画成本治理与最终验收守卫。
