package org.example.aichat.rp;

/** Prompt policy is separate from persistence and rollback operations. */
public final class RpPromptBuilder {
  private RpPromptBuilder() {}

  public static String build(RpState state, String encoded, String events) {
    return "\n必须先给用户非空的可见台词，直接回应本轮消息。不能只输出场景 JSON 或情绪标签。<scene> 状态是可选的，只能在台词之后追加，不得代替回答。\n【持续的共同生活】\n"
        + encoded
        + "\n【有出处的最近经历】\n"
        + events
        + "\n"
        + "以上是当前世界线。不要把其他故事当成这里发生过的事。用户身份指故事身份。保持地点、物品、衣着与动作连续；未提供的细节不要假装记得。\n"
        + "关系以这里的设定为准；即使已是恋人，也有自己的心情、判断、责任和计划，关系变化应有事件依据，不强制刷好感。\n"
        + ("story".equals(state.style)
            ? "以台词和简短的（动作/环境）演绎，不替用户决定行动或心理。按情节需要调整长度。\n"
            : "自然短聊，动作轻短，不反复套用比喻。\n")
        + "场外指导只用于编排，不当作角色经历："
        + state.directorNote
        + "\n"
        + "回复结束时可追加一个不朗读、不展示的"
        + " <scene>JSON</scene>，只更新本轮确实改变的字段：location,storyTime,participants,outfit,objects,action,relationship,mood,characterGoal,sharedActivity,events,commitments。"
        + "events与commitments若更新须保留仍有效条目；约定标记待履行/完成/取消。提议不是已发生事件，不可擅自推进用户行动。不要在JSON中写台词。";
  }
}
