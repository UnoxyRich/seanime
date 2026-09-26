import { useServerMutation } from "@/api/client/requests"
import { useLibraryExplorer } from "@/app/(main)/_features/library-explorer/library-explorer.atoms"
import { OpenInExplorer_Variables } from "@/api/generated/endpoint.types"
import { API_ENDPOINTS } from "@/api/generated/endpoints"
import { __isAndroidTV__ } from "@/types/constants"

export function useOpenInExplorer() {
    const mutation = useServerMutation<boolean, OpenInExplorer_Variables>({
        endpoint: API_ENDPOINTS.EXPLORER.OpenInExplorer.endpoint,
        method: API_ENDPOINTS.EXPLORER.OpenInExplorer.methods[0],
        mutationKey: [API_ENDPOINTS.EXPLORER.OpenInExplorer.key],
        onSuccess: async () => {

        },
    })
    const { openDirInLibraryExplorer } = useLibraryExplorer()

    const mutate: typeof mutation.mutate = (variables, options) => {
        if (__isAndroidTV__) {
            openDirInLibraryExplorer(variables.path)
            return
        }
        mutation.mutate(variables, options)
    }
    const mutateAsync: typeof mutation.mutateAsync = async (variables, options) => {
        if (__isAndroidTV__) {
            openDirInLibraryExplorer(variables.path)
            return true
        }
        return mutation.mutateAsync(variables, options)
    }

    return { ...mutation, mutate, mutateAsync }
}
