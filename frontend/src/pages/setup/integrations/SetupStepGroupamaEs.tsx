import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { PiggyBank } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

export function SetupStepGroupamaEs() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const acknowledge = useAcknowledgeIntegration()
  const selected = useSetupFlowStore(state => state.selectedIntegrations)
  const markDone = useSetupFlowStore(state => state.markIntegrationDone)

  const proceed = async () => {
    try {
      await acknowledge.mutateAsync('groupamaes')
      markDone('groupamaes')
    } catch {
      // Best effort: the integration can still be enabled from Administration.
    }
    navigate(nextIntegrationRoute('groupamaes', selected))
  }

  const skip = () => navigate(nextIntegrationRoute('groupamaes', selected))

  return (
    <div className="space-y-8">
      <div className="space-y-2 text-center">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <PiggyBank className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          {t('setup.groupamaEs.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.groupamaEs.body')}
        </p>
      </div>
      <div className="rounded-2xl border border-border/60 bg-muted/30 p-4 text-center text-xs text-muted-foreground">
        {t('setup.groupamaEs.tip')}
      </div>
      <div className="mx-auto flex w-full max-w-lg flex-col gap-3">
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          className="w-full rounded-full"
        >
          {t('setup.groupamaEs.skip')}
        </Button>
        <Button
          onClick={proceed}
          disabled={acknowledge.isPending}
          className="w-full rounded-full"
        >
          {t('setup.groupamaEs.cta')}
        </Button>
      </div>
    </div>
  )
}
