package com.shopify.volumizer.utils;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import timber.log.Timber;

abstract class DefaultObserver<T> implements Observer<T> {

        Consumer<Disposable> onSubscribeConsumer = disposable -> {};
        Consumer<T> onNextConsumer = item -> {};
        Action onCompleteConsumer = () -> Timber.i("mainThreadActionQueue onComplete() called.");
        Consumer<Throwable> onErrorConsumer = Timber::e ;

        // TODO: Constructor override

        @Override
        public void onSubscribe(Disposable d) {
        }

        @Override
        public void onError(Throwable e) {
            Timber.e(e);
        }

        @Override
        public void onComplete() {
            Timber.i("mainThreadActionQueue onComplete() called.");
        }
    }