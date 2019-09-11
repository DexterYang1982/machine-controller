package net.gridtech.machine.controller.group

import io.reactivex.Observable
import net.gridtech.core.util.currentTime
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.controller.utils.ReadWriteService
import net.gridtech.machine.model.entity.Group
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct


@Service
class GroupTriggerService {
    @Autowired
    lateinit var bootService: BootService
    @Autowired
    lateinit var readWriteService: ReadWriteService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Group }.map { it as Group }.subscribe { group ->
            val disposable =
                    group.description.observable.switchMap { groupDefinition ->
                        Observable.merge(groupDefinition.triggers.map { trigger ->
                            readWriteService.readConditionObservable(trigger.condition).delay(trigger.delay, TimeUnit.MILLISECONDS).map {
                                trigger
                            }
                        })
                    }.subscribe { trigger ->
                        val triggerPoint = readWriteService.readConditionFieldValues(trigger.condition).mapNotNull {
                            it.second
                        }.maxBy { it.updateTime }

                        if (currentTime() - (triggerPoint?.updateTime ?: 0) < trigger.timeout) {
                            val session = triggerPoint?.session ?: ""
                            trigger.writes.forEach { entityWrite ->
                                readWriteService.executeEntityWrite(entityWrite, session)
                            }
                        }
                    }

            group.onDelete().subscribe { _, _ ->
                disposable.dispose()
            }
        }
    }
}