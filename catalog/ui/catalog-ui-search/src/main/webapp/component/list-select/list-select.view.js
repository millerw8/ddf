/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
/*global define, alert*/
var Marionette = require('marionette');
var _ = require('underscore');
var $ = require('jquery');
var CustomElements = require('js/CustomElements');
var ListItemCollectionView = require('component/list-item/list-item.collection.view');
var MenuNavigationDecorator = require('decorator/menu-navigation.decorator');
var Decorators = require('decorator/Decorators');

var eventsHash = {
    'click': 'handleClick'
};

var namespace = CustomElements.getNamespace();
var listItemClickEvent = 'click '+namespace+'list-item';
eventsHash[listItemClickEvent] = 'handleListItemClick';

module.exports = ListItemCollectionView.extend(Decorators.decorate({
    className: 'is-list-select is-action-list',
    events: eventsHash,
    onBeforeShow: function(){
        this.handleValue();
    },
    handleListItemClick: function(event){
        this.model.set('value', $(event.currentTarget).attr('data-listid'));
        this.handleValue();
    },
    handleClick: function(){
        this.$el.trigger('closeDropdown.'+CustomElements.getNamespace());
    },
    handleValue: function(){
        var listId = this.model.get('value');
        this.$el.find(namespace+'list-item').removeClass('is-selected');
        if (listId){
            this.$el.find(namespace+'list-item[data-listid="'+listId+'"]').addClass('is-selected');
        }
    },
    onRender: function(){
        if (this.$el.find('.is-active').length === 0){
            this.$el.find(namespace+'list-item').first().addClass('is-active');
        }
    }
}, MenuNavigationDecorator));