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
/*global define, require, module*/
var Marionette = require('marionette');
var _ = require('underscore');
var _merge = require('lodash/merge');
var $ = require('jquery');
var template = require('./list-item.hbs');
var CustomElements = require('js/CustomElements');
require('behaviors/button.behavior');
var DropdownView = require('component/dropdown/popout/dropdown.popout.view');
var ListEditorView = require('component/list-editor/list-editor.view');
var QueryFeedView = require('component/query-feed/query-feed.view');
var ListInteractionsView = require('component/list-interactions/list-interactions.view');

module.exports = Marionette.LayoutView.extend({
  tagName: CustomElements.register('list-item'),
  template: template,
  attributes: function() {
    return {
      'data-listid': this.model.id
    };
  },
  regions: {
    listEdit: '.list-edit',
    queryFeed: '.details-feed',
    listActions: '.list-actions'
  },
  events: {
    'click .list-run': 'runList',
    'click .list-stop': 'stopList',
    'click .list-delete': 'deleteList'
  },
  behaviors: {
    button: {}
  },
  initialize: function() {
    if (this.model.get('query').has('result')) {
      this.startListeningToStatus();
    } else {
      this.listenTo(this.model.get('query'), 'change:result', this.resultAdded);
    }
    this.listenTo(this.model, 'change:list.bookmarks', this.handleEmptyList);
    this.handleEmptyList();
  },
  handleEmptyList: function() {
    this.$el.toggleClass('is-empty', this.model.isEmpty());
  },
  onRender: function() {
    this.setupEdit();
    this.setupFeed();
    this.setupListActions();
  },
  setupListActions: function() {
    this.listActions.show(DropdownView.createSimpleDropdown({
      componentToShow: ListInteractionsView,
      modelForComponent: this.model,
      leftIcon: 'fa fa-ellipsis-v'
    }))
  },
  setupFeed: function() {
    this.queryFeed.show(new QueryFeedView({
      model: this.model.get('query')
    }));
  },
  setupEdit: function() {
    this.listEdit.show(DropdownView.createSimpleDropdown({
      componentToShow: ListEditorView,
      modelForComponent: this.model,
      leftIcon: 'fa fa-pencil'
    }));
  },
  resultAdded: function(model) {
    if (this.model.get('query').has('result') && _.isUndefined(this.model.get('query').previous('result'))) {
      this.startListeningToStatus();
    }
  },
  startListeningToStatus: function() {
    this.handleStatus();
    this.listenTo(
      this.model.get('query').get('result'),
      'sync request error',
      this.handleStatus
    );
  },
  handleStatus: function() {
    this.$el.toggleClass(
      'is-searching',
      this.model.get('query').get('result').isSearching()
    );
  },
  runList: function(e) {
    this.model.get('query').startSearch();
    e.stopPropagation();
  },
  stopList: function(e) {
    this.model.get('query').cancelCurrentSearches();
    e.stopPropagation();
  },
  deleteList: function(e) {
    this.model.get('query').cancelCurrentSearches();
    this.model.collection.remove(this.model);
    e.stopPropagation();
  },
  serializeData: function() {
    return _merge(this.model.toJSON({
      additionalProperties: ['cid', 'color']
    }), {
      icon: this.model.getIcon()
    });
  }
});
