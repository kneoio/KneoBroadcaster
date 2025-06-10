package io.kneo.broadcaster.dto.actions;

import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.actions.ActionsFactory;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IRole;

import java.util.List;

public class AiAgentActionsFactory {

    public static ActionBox getViewActions(List<IRole> activatedRoles) {
        ActionBox actions = ActionsFactory.getDefaultViewActions(LanguageCode.en);
        /*Action action = new Action();
        action.setIsOn(RunMode.ON);
        action.setCaption("new_project");
        actions.addAction(action);*/
        return actions;
    }

}
