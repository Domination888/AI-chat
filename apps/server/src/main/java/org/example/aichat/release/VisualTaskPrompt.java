package org.example.aichat.release;

/** Uploaded pixels and the current question take priority over role-play examples. */
final class VisualTaskPrompt {
  private VisualTaskPrompt() {}

  static String context(Object role, String persona, String records, String sources, String constraints) {
    String identity = "你以" + role + "的自然口吻回应用户上传的图片和本轮问题。\n";
    String instruction = "\n【本轮图片任务】\n本轮用户已上传图片。先直接回答用户对图片的提问，依据实际可见的画面；角色背景、旧开场、旧对话和定时通知都不能代替图片证据或本轮任务。看不清或不能确认的细节应说明不确定，不凭角色设定补造。用户要求创作或角色反应时可以发挥，但要区分画面事实与想象；用户只问物品、颜色等事实时，不转入无关剧情，不替用户行动。保持原问题的对象和要求，不把画面人物自动当成你或用户。\n";
    // Keep the task requirements at the end, with space reserved for visual grounding.
    String task = PromptBudget.trim(constraints, Math.max(0, 2400 - PromptBudget.tokens(identity + instruction) - 80)) + instruction;
    int remaining = Math.max(0, 2400 - PromptBudget.tokens(identity + task) - 80);
    String background = (sources.isBlank() ? "" : "\n【本轮外部资料】\n" + PromptBudget.trim(sources, 650))
        + (records.isBlank() ? "" : "\n【旧记录，仅用于理解指代】\n" + PromptBudget.trim(records, 300))
        + "\n【角色背景，只影响语气，不是图片事实】\n" + PromptBudget.trim(persona, 180);
    return identity + PromptBudget.trim(background, remaining) + task;
  }
}
