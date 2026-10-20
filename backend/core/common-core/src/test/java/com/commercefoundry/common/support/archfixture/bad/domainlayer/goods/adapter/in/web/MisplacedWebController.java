package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.service.GoodsWebFacade;
import org.springframework.web.bind.annotation.RestController;

/** H1 — controller 패키지 밖의 @RestController 도 컨트롤러로 본다. Facade 를 직접 쓴다. */
@RestController
public class MisplacedWebController {

    private final GoodsWebFacade goodsWebFacade;

    public MisplacedWebController(GoodsWebFacade goodsWebFacade) {
        this.goodsWebFacade = goodsWebFacade;
    }

    public Object getGoods(long goodsId) {
        return goodsWebFacade.getGoods(goodsId);
    }
}
