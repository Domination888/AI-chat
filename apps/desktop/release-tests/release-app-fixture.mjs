import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const folder = new URL('../src/release/', import.meta.url)
const read = name => readFileSync(new URL(name, folder), 'utf8')

// The existing snippet tests use the app's setup order. Read the actual
// composable bodies and template from their source files in that order.
export function releaseAppSource() {
  return read('ReleaseApp.vue')
    .replace('<template src="./ReleaseAppView.html"></template>', () => '<template>\n' + read('ReleaseAppView.html') + '</template>')
    .replace(/^(?:const \{ [^\n]+ \} = )?(useRelease(?:Dialogs|Guide|Session|Stream|History|Account|Startup))\(\{\n[\s\S]*?^\}\)\n/gm, (_, name) => {
      const source = read(name + '.mjs')
      const start = source.indexOf('}) {\n') + 5
      const returns = source.lastIndexOf('\n  return { ')
      const end = returns < 0 ? source.lastIndexOf('\n}') : returns
      return source.slice(start, end).replace(/^  /gm, '').trimEnd() + '\n'
    })
}

export function runReleaseSnippet(source, context) {
  return runInNewContext(source, Object.assign(context, { replyState: context }))
}
