import test from 'node:test'
import assert from 'node:assert/strict'
import { Live2DController } from '../src/live2d/live2d-controller.js'

function fixture() {
  const expression = {}, natural = {}
  const manager = {
    expressions: [expression], currentExpression: natural, defaultExpression: natural,
    getExpressionIndex: name => name === '闭眼' ? 0 : -1,
    playing: null, reserveExpressionIndex: -1,
    stopAllExpressions() { this.playing = null },
    restoreExpression() { this.playing = this.currentExpression },
    resetExpression() { this.playing = natural },
  }
  const motionManager = { expressionManager: manager, state: {}, stopAllMotions() { this.state = {} } }
  const model = {
    internalModel: { motionManager },
    async expression(name) {
      if (name !== '闭眼' || manager.currentExpression === expression) return false
      manager.currentExpression = expression; manager.playing = expression; return true
    },
    async motion(group, index) {
      if (motionManager.state.currentGroup === group && motionManager.state.currentIndex === index) return false
      motionManager.state = { currentGroup: group, currentIndex: index }; return true
    },
  }
  const controller = new Live2DController(); controller.setModel(model)
  return { controller, model, manager, expression, natural }
}

test('selecting the current expression again keeps it visible', async () => {
  const { controller, manager, expression } = fixture()
  assert.equal(await controller.triggerExpression('闭眼', { manual: true }), true)
  assert.equal(await controller.triggerExpression('闭眼', { manual: true }), true)
  assert.equal(manager.playing, expression)
})

test('manual expression survives queued emotion and conversation resets', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { controller, manager, expression } = fixture()
  controller._scheduleEmotionReset(); controller.onConversationEnd()
  assert.equal(await controller.triggerExpression('闭眼', { manual: true }), true)
  t.mock.timers.tick(4000)
  assert.equal(manager.playing, expression)
  controller.onConversationEnd(); t.mock.timers.tick(3000)
  assert.equal(manager.playing, expression)
})

test('old model timers cannot reset a newly opened character', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const first = fixture(), next = fixture()
  first.controller._scheduleEmotionReset(); first.controller.onConversationEnd()
  first.controller.clearModel(); first.controller.setModel(next.model)
  await first.controller.triggerExpression('闭眼')
  t.mock.timers.tick(4000)
  assert.equal(next.manager.playing, next.expression)
})

test('asynchronous failures and stale models return failure to the controls', async () => {
  const { controller, model } = fixture()
  model.expression = async () => { throw new Error('asset load failed') }
  assert.equal(await controller.triggerExpression('missing'), false)
  model.motion = async () => { throw new Error('motion load failed') }
  assert.equal(await controller.triggerMotion({ group: 'missing', index: 0 }, true, 3), false)
  model.expression = () => new Promise(resolve => { model.resolve = resolve })
  const result = controller.triggerExpression('闭眼'); controller.clearModel(); model.resolve(true)
  assert.equal(await result, false)
})

test('manual playback can replay the same motion and natural reset clears the expression', async () => {
  const { controller, manager, natural } = fixture()
  const motion = { group: 'Tap#4', index: 7 }
  assert.equal(await controller.triggerMotion(motion, true, 3), true)
  assert.equal(await controller.triggerMotion(motion, true, 3), true)
  await controller.triggerExpression('闭眼')
  assert.equal(controller.resetExpression({ manual: true }), true)
  assert.equal(manager.currentExpression, natural)
  assert.equal(manager.playing, natural)
})
