export const allLive2dMotions = [
  { name: '待机', group: 'Idle', index: 0 },
  { name: '入场', group: 'Entry', index: 0 },
  { name: '种到土里', group: 'Tap#4', index: 0 },
  { name: '种到地里', group: 'Tap#4', index: 1 },
  { name: '谷种入田野', group: 'Tap#4', index: 2 },
  { name: '钓鱼', group: 'Tap#4', index: 3 },
  { name: '掐腰', group: 'Tap#4', index: 4 },
  { name: '有大麟', group: 'Tap#4', index: 5 },
  { name: '生气', group: 'Tap#4', index: 6 },
  { name: '晃手', group: 'Tap#4', index: 7 },
  { name: '闭眼', group: 'Tap#4', index: 8 },
]

export const allLive2dExpressions = ['闭眼', '皱眉', '闭一只眼', '震惊']

export const clickLive2dMotions = allLive2dMotions.filter(m =>
  ['晃手', '种到地里', '谷种入田野', '钓鱼'].includes(m.name)
)
