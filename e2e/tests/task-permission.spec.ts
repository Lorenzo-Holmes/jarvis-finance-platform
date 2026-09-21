import { test as base } from '@playwright/test'
import { TASK_ACTIVE, TASK_PAUSED, TaskPermissionPage } from '../pages/TaskPermissionPage'

/**
 * 系统管理域 · **定时任务的权限置灰契约**。
 *
 * ── 被测行为（后端 `ScheduledTaskController` + 前端 `ScheduledTasksPage.vue`）──────
 * 后端只拦「会消耗资源」的四个动作：创建 / 编辑 / 恢复 / 立即执行；
 * 列表、详情、历史、暂停、删除**刻意不拦** —— 否则用户被收回权限后，
 * 连关掉自己还在跑的任务都做不到。前端必须与这条边界保持一致，在渲染阶段就置灰。
 *
 * ── 本文件钉住三件事 ──────────────────────────────────────────────
 * 1. `can_manage:false` 时，**恰好那四个**写操作置灰，其余一律可用（两条都测，防止过度拦）；
 * 2. `can_manage:true` 时全部可用，且不显示权限说明；
 * 3. **能力探测失败时默认放行** —— 只在明确拿到 `false` 才置灰，
 *    避免探测本身出问题时误伤本来能用的账号（真正的门禁始终在服务端）。
 *
 * ── 为什么不依赖后端 ─────────────────────────────────────────────
 * 全部对 `/api/` 打桩，因此本地只跑 Vite 即可；也不受线上数据变化影响而假失败。
 *
 * ── 为什么用文件内的 fixture 而不是 `fixtures/test.ts` ──────────────
 * 该页对象只服务这一条契约，就地构造可避免改动跨组共享的 fixture 文件。
 *
 * 运行：`npm run test:permissions`（project `permissions`，固定 `--workers=1`）。
 */

type Fixtures = { taskPermissionPage: TaskPermissionPage }

const test = base.extend<Fixtures>({
  taskPermissionPage: async ({ page }, use) => {
    await use(new TaskPermissionPage(page))
  },
})

test.describe('定时任务 · 权限置灰', () => {
  test('无权限时，四个消耗资源的写操作应全部置灰', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: false })
    await taskPermissionPage.openTaskWorkspace()

    await taskPermissionPage.expectWriteActionsGated()
  })

  test('无权限时，刷新/历史/暂停/删除仍应可用', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: false })
    await taskPermissionPage.openTaskWorkspace()

    // 这一条与上一条同等重要：过度拦会让用户连「停掉在跑的任务」都做不到。
    await taskPermissionPage.expectNonWriteActionsUsable()
  })

  test('无权限时应给出可读的说明，而不是让按钮默默点不动', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: false })
    await taskPermissionPage.openTaskWorkspace()

    await taskPermissionPage.expectVisible(
      taskPermissionPage.permissionNotice,
      '无权限时页面应说明为什么写操作被置灰',
    )
    await taskPermissionPage.expectText(
      taskPermissionPage.permissionNotice,
      /可以查看任务与执行历史、暂停或删除自己的任务/,
      '说明应讲清「仍能做什么」，而不只是「不能做什么」',
    )
  })

  test('置灰是真禁用：强行点击不应打开编辑器', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: false })
    await taskPermissionPage.openTaskWorkspace()

    // 只断言 `disabled` 属性存在，仍可能被「只调了透明度」的样式改动骗过，
    // 所以再强制点一次，确认处理函数确实没被触发。
    await taskPermissionPage.expectDisabledClickDoesNothing(TASK_ACTIVE.name, '编辑')
  })

  test('有权限时写操作全部可用，且不显示权限说明', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: true })
    await taskPermissionPage.openTaskWorkspace()

    await taskPermissionPage.expectWriteActionsUsable()
    await taskPermissionPage.expectHidden(
      taskPermissionPage.permissionNotice,
      '有权限时不应出现权限说明条',
    )
  })

  test('能力探测失败时应默认放行，不误伤本来能用的账号', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: 'probe-failed' })
    await taskPermissionPage.openTaskWorkspace()

    // 关键设计：只有明确拿到 can_manage:false 才置灰。探测失败若也置灰，
    // 后端一抖动用户就会以为「自己没权限」；而真正的门禁始终在服务端，放行不会造成越权。
    await taskPermissionPage.expectWriteActionsUsable()
    await taskPermissionPage.expectHidden(
      taskPermissionPage.permissionNotice,
      '探测失败时不应误报「未开通权限」',
    )
  })

  test('权限只影响写操作，不影响任务列表本身', async ({ taskPermissionPage }) => {
    await taskPermissionPage.stubPermission({ canManage: false, tasks: [TASK_ACTIVE, TASK_PAUSED] })
    await taskPermissionPage.openTaskWorkspace()

    // 无权限也必须能读到自己的任务与最近一次执行结果。
    await taskPermissionPage.expectVisible(
      taskPermissionPage.row(TASK_PAUSED.name),
      '无权限时仍应能看到已暂停的任务',
    )
    await taskPermissionPage.expectVisible(
      taskPermissionPage.row('上游行情源超时'),
      '无权限时仍应能看到最近一次失败原因',
    )
  })
})
