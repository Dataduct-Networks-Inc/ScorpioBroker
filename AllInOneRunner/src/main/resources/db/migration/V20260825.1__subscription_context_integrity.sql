ALTER TABLE public.subscriptions
    ADD CONSTRAINT subscriptions_context_required_chk
    CHECK (context IS NOT NULL) NOT VALID;

ALTER TABLE public.subscriptions
    ADD CONSTRAINT subscriptions_context_fkey
    FOREIGN KEY (context)
    REFERENCES public.contexts (id)
    ON DELETE RESTRICT
    NOT VALID;
