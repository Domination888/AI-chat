export function createPanelRefresh(transport){
 let epoch=0
 return {version:()=>epoch,invalidate(){epoch++},request(){const current=epoch;return async(...args)=>{const result=await transport(...args);if(current!==epoch)throw new Error('面板已关闭');return result}}}
}
