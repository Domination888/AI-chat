// Keep short introductions with the following sentence for natural, stable synthesis.
export function speechChunks(input,flush=false){
 let part='',chunks=[],start=0,depth=0
 const spoken=value=>value.replace(/（[^）]*）|\([^)]*\)/g,'').replace(/[^\p{L}\p{N}]/gu,'')
 for(let i=0;i<input.length;i++){const char=input[i];if(char==='（'||char==='(')depth++;else if(char==='）'||char===')')depth=Math.max(0,depth-1)
  if(depth===0&&/[。！？!?\n]/.test(char)){part+=input.slice(start,i+1);start=i+1;if(spoken(part).length>=12){chunks.push(part.trim());part=''}}}
 const rest=part+input.slice(start);if(flush)return {chunks:spoken(rest)?[...chunks,rest.trim()]:chunks,rest:''}
 return {chunks,rest}
}
