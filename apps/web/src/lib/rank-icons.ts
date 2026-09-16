import {
  Baby,
  BookPlus,
  Building2,
  Crown,
  Flame,
  Ghost,
  GraduationCap,
  Headphones,
  Heart,
  Hourglass,
  Landmark,
  ListOrdered,
  Mars,
  Rocket,
  Sword,
  ThumbsUp,
  TrendingUp,
  Trophy,
  Venus,
  WandSparkles,
  type LucideIcon,
} from 'lucide-react'

// 上游榜单名称不稳定（可能新增/改名），用关键词匹配而不是固定 id；
// 顺序即优先级，更具体的词必须排在「热播」「VIP」等泛化词之前。
const RANK_ICON_RULES: Array<[RegExp, LucideIcon]> = [
  [/儿童/, Baby],
  [/新书/, BookPlus],
  [/会员|VIP/i, Crown],
  [/口碑/, ThumbsUp],
  [/畅销/, TrendingUp],
  [/悬疑|推理/, Ghost],
  [/都市|现代/, Building2],
  [/玄幻|奇幻/, WandSparkles],
  [/仙侠|武侠/, Sword],
  [/言情|情感/, Heart],
  [/穿越/, Hourglass],
  [/历史/, Landmark],
  [/科幻/, Rocket],
  [/校园/, GraduationCap],
  [/男频/, Mars],
  [/女频/, Venus],
  [/有声小说|听书/, Headphones],
  [/热播|热门/, Flame],
  [/总榜/, Trophy],
]

export function rankIcon(name: string): LucideIcon {
  for (const [pattern, icon] of RANK_ICON_RULES) {
    if (pattern.test(name)) return icon
  }
  return ListOrdered
}
