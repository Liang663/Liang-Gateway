import { ref } from "vue";
import { errorText } from "./api";
export function useTask() {
  const busy = ref(false),
    error = ref(""),
    success = ref("");
  async function run(task: () => Promise<void>, message = "") {
    if (busy.value) return false;
    busy.value = true;
    error.value = "";
    success.value = "";
    try {
      await task();
      success.value = message;
      return true;
    } catch (e) {
      error.value = errorText(e);
      return false;
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, success, run };
}
