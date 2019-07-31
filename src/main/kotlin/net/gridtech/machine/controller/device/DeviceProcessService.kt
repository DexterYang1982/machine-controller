package net.gridtech.machine.controller.device

import io.reactivex.Observable
import net.gridtech.core.util.cast
import net.gridtech.core.util.currentTime
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.controller.utils.ReadWriteService
import net.gridtech.machine.model.entity.Device
import net.gridtech.machine.model.entity.Tunnel
import net.gridtech.machine.model.entityField.ProcessState
import net.gridtech.machine.model.entityField.StepRuntime
import net.gridtech.machine.model.entityField.StepState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Service
class DeviceProcessService {
    @Autowired
    lateinit var bootService: BootService
    @Autowired
    lateinit var readWriteService: ReadWriteService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Device }.map { cast<Device>(it)!! }.subscribe { device ->
            val disposable = device.entityClass.currentProcess.getFieldValue(device).observable
                    .switchMap { processRuntime ->
                        when (processRuntime.state) {
                            ProcessState.QUEUED -> {
                                if (processRuntime.delay > 0) {
                                    Observable.just(ProcessNextAction.WAIT)
                                } else {
                                    Observable.just(ProcessNextAction.RUN)
                                }
                            }
                            ProcessState.WAITING -> {
                                Observable.timer(processRuntime.delay, TimeUnit.MILLISECONDS).map { ProcessNextAction.RUN }
                            }
                            ProcessState.RUNNING -> {
                                processRuntime.stepRuntime.lastOrNull()
                                        ?.let { currentStep ->
                                            device.getProcessById(processRuntime.deviceProcessId)?.let { deviceProcess ->
                                                deviceProcess.steps.find { it.id == currentStep.stepId }
                                            }
                                        }?.let { deviceProcessStep ->
                                            if (device.entityClass.healthy.getFieldValue(device).value == false) {
                                                Observable.just(ProcessNextAction.ERROR)
                                            } else {
                                                deviceProcessStep.execute?.apply {
                                                    val session = device.entityClass.currentProcess.getFieldValue(device).session
                                                    readWriteService.executeEntityWrite(this, session)
                                                }
                                                Observable.merge(
                                                        readWriteService.readConditionObservable(deviceProcessStep.endCondition)
                                                                .filter { it }
                                                                .map { ProcessNextAction.FINISH },
                                                        Observable.timer(deviceProcessStep.timeout, TimeUnit.SECONDS)
                                                                .map { ProcessNextAction.TIMEOUT }
                                                )
                                            }
                                        } ?: Observable.just(ProcessNextAction.ERROR)
                            }
                            else -> Observable.just(ProcessNextAction.NONE)
                        }
                    }
                    .filter { it != ProcessNextAction.NONE }
                    .subscribe { nextAction ->
                        val currentProcessFieldValue = device.entityClass.currentProcess.getFieldValue(device)
                        val processRuntime = currentProcessFieldValue.value!!.copy()

                        when (nextAction) {
                            ProcessNextAction.WAIT -> {
                                processRuntime.state = ProcessState.WAITING
                            }
                            ProcessNextAction.RUN, ProcessNextAction.FINISH -> {
                                if (nextAction == ProcessNextAction.FINISH) {
                                    processRuntime.stepRuntime.lastOrNull()?.apply {
                                        state = StepState.FINISHED
                                        endTime = currentTime()
                                    }
                                }
                                val nextStep = device.getProcessById(processRuntime.deviceProcessId)
                                        ?.steps
                                        ?.find { deviceProcessStep ->
                                            readWriteService.readConditionValue(deviceProcessStep.executeCondition)
                                        }
                                if (nextStep != null) {
                                    processRuntime.state = ProcessState.RUNNING
                                    processRuntime.stepRuntime = processRuntime.stepRuntime.toMutableList().apply {
                                        add(StepRuntime(
                                                stepId = nextStep.id,
                                                state = StepState.RUNNING,
                                                startTime = currentTime(),
                                                endTime = null
                                        ))
                                    }
                                } else {
                                    processRuntime.state = ProcessState.FINISHED
                                }
                            }
                            ProcessNextAction.ERROR -> {
                                processRuntime.stepRuntime.lastOrNull()?.apply {
                                    state = StepState.ERROR
                                    endTime = currentTime()
                                }
                                processRuntime.state = ProcessState.ERROR
                            }
                            ProcessNextAction.TIMEOUT -> {
                                processRuntime.stepRuntime.lastOrNull()?.apply {
                                    state = StepState.TIMEOUT
                                    endTime = currentTime()
                                }
                                processRuntime.state = ProcessState.ERROR
                            }
                            else -> {
                            }
                        }
                        processRuntime.tunnelId
                                ?.let { bootService.dataHolder.getEntityByIdObservable<Tunnel>(it) }
                                ?.subscribe { tunnel, _ ->
                                    tunnel.updateTunnelProcessState(processRuntime)
                                }
                        currentProcessFieldValue.update(processRuntime, currentProcessFieldValue.session)
                    }
            device.onDelete().subscribe { _, _ ->
                disposable.dispose()
            }
        }
    }

    enum class ProcessNextAction {
        NONE,
        WAIT,
        RUN,
        ERROR,
        TIMEOUT,
        FINISH
    }
}