Collection = require './collection'

Request = require '../models/Request'

class Responses extends Collection

    model: Request

    url: => "#{ config.apiRoot }/request/history/#{ @serviceId }"

    initialize: (models, { @serviceId }) =>

module.exports = Responses
