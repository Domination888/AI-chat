// Embeddings consume one complete sequence; keep its physical batch at least
// as large as the configured context rather than the runtime's 512 default.
const EMBEDDING_CONTEXT=4096
function embeddingArgs(common,port,model,alias){return [...common,'--port',String(port),'--model',model,'--alias',alias,'--embedding','--pooling','last','--ctx-size',String(EMBEDDING_CONTEXT),'--parallel','1','--batch-size',String(EMBEDDING_CONTEXT),'--ubatch-size',String(EMBEDDING_CONTEXT)]}
module.exports={embeddingArgs,EMBEDDING_CONTEXT}
