# Android 官方交互与反馈改造

用户已确认：统一采用 Android / Material 官方推荐交互和反馈；同时完成日期/时间输入、顶部栏、表单、返回手势、自适应布局。本轮不增加无障碍专项改造，保留已有组件语义与配色约定。

后续确认：所有界面稍微紧凑。统一页边距从 20dp 收至 16dp，常用间隔从 12dp 收至 8dp，设置卡片内边距从 16dp 收至 12dp，设置行最小高度从 64dp 收至 56dp。移除无错误时的空 supportingText 槽；预设和模式选项流式排列。标准控件触摸区域和文字尺寸保持不变。

## 页面与导航

统一 `ScreenFrame` 使用 Material 3 `TopAppBar`、返回图标、默认状态反馈及嵌套滚动行为。使用 `NavigationSuiteScaffold` 随窗口大小切换底部导航/导航栏；详情页隐藏一级导航。内容居中并限制阅读宽度，表单处理 IME、系统栏和滚动焦点。启用预测返回，保留 Navigation Compose 默认返回动画；有未保存草稿时使用支持预测返回的处理，在完成手势后提示保存/放弃/继续编辑。

## 输入

共用 DatePicker / DateRangePicker / TimePicker（可切换 TimeInput）组件；工作、免打扰、日期覆盖改为可添加/删除的时段列表，明确跨午夜。日期选择使用 UTC 日历日期编码，保存时仍由业务时区解释。请假编辑保留原始 epoch；未改时间时不得丢失毫秒、DST 重叠偏移。不存在的本地时刻不能静默平移。号码使用电话键盘，表单提供 Next/Done，选择项采用标准菜单。复杂编辑器使用可滚动的 Material Dialog 与固定操作区。

## 反馈

Scaffold/独立窗口内提供 SnackbarHost。用户发起操作成功才显示一次、描述具体操作结果的 Snackbar；不得在重组、恢复、返回页面时重播。开关以实际保存结果为准，提交时禁用相关操作，失败保留原状态。字段错误就地显示，操作错误保留可执行的修正入口；初次加载和刷新使用标准进度组件，保留可用旧内容。删除与恢复默认使用 AlertDialog 确认；没有恢复 API 的操作不展示撤销。权限按需使用系统授权，应用内部错误通过 Snackbar/页面提示。

## 验证

先验证现有单元测试基线。新增行为测试覆盖反馈只显示一次、整行开关、确认后删除/重置、时段选择和取消、日期与时区转换、请假精度、草稿退出及窗口变化。运行 UI 单元测试、instrumentation APK 构建、lint、应用 APK 构建；如设备已解锁，使用隔离 UI 测试包实机执行，不覆盖生产应用。真实系统返回动画与键盘显示必须以设备证据说明。

## 官方依据

- https://developer.android.com/develop/ui/compose/components/snackbar
- https://developer.android.com/develop/ui/compose/components/dialog
- https://developer.android.com/develop/ui/compose/components/datepickers
- https://developer.android.com/develop/ui/compose/components/time-pickers
- https://developer.android.com/develop/ui/compose/components/app-bars
- https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
- https://developer.android.com/develop/ui/compose/system/predictive-back-setup
- https://developer.android.com/develop/ui/compose/system/insets-ui
