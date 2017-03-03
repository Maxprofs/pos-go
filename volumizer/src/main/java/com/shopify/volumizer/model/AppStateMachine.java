package com.shopify.volumizer.model;

import com.shopify.volumizer.model.StateMachine.State;

import javax.inject.Singleton;

/**
 */
@Singleton
public class AppStateMachine {

    private final StateMachine<Event> stateMachine;

    public AppStateMachine() {
        this.stateMachine = buildStateMachine();
    }

    private StateMachine<Event> buildStateMachine() {
        StateMachine<Event> sm = new StateMachine<>(AppState.STARTING);

        return sm;
    }

    enum Event {
        ADD, DELETE, CLIP ;
    }

    enum AppState implements Supplier<State<Event>> {
        STARTING,
        ADDING,
        DELETING,
        CLIPPING;

        private final State<Event> state;

        AppState() {
            this.state = new State<>(this.name());
        }

        @Override
        public State<Event> get() {
            return state;
        }
    }
}
