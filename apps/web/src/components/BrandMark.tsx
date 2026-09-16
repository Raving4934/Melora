/** 品牌标记：均衡器声柱（与桌面/启动图标稿同源，颜色随 currentColor 自适应）。 */
export function BrandMark({ size = 22, strokeWidth = 2.2 }: { size?: number; strokeWidth?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M5.6 13.5v-3M9 16.5V7.5M12.4 18.75V5.25M15.75 16.1V7.9M19.1 13.5v-3" />
    </svg>
  )
}
