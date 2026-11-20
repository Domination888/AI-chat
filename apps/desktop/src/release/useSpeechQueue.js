import {ref} from 'vue'
import {request} from './api.mjs'
import {live2dController} from '../live2d/live2d-controller.js'
import {createSpeechQueue} from './speechQueue.mjs'

export function useSpeechQueue({roleId,onError}) {
  return createSpeechQueue({
    phase:ref('idle'),speaking:ref(false),enabled:ref(true),roleId,onError,request,
    createContext:()=>new AudioContext(),
    lipSync:{start:(context,source)=>live2dController.startLipSync(context,source),stop:()=>live2dController.stopLipSync()},
  })
}
